package com.fashion.catalog;

import com.fashion.catalog.HuggingFaceGradioClient.ProviderException;
import com.fashion.security.CustomerAuthController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.sql.Timestamp;


@Service
public class VirtualTryOnService {
    private static final int MAX_PIXELS = 16_000_000;
    private final JdbcTemplate db;
    private final CustomerAuthController auth;
    private final HuggingFaceGradioClient gradio;
    private final long maxUploadBytes;
    private final Path resultDirectory;
    private final HttpClient imageClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    private static final Logger log =
        LoggerFactory.getLogger(VirtualTryOnService.class);
    
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
        Thread thread = new Thread(task, "atelier-virtual-tryon"); thread.setDaemon(true); return thread;
    });


    public VirtualTryOnService(JdbcTemplate db, CustomerAuthController auth, HuggingFaceGradioClient gradio,
                               @Value("${atelier.try-on.upload-max-bytes:5242880}") long maxUploadBytes) {
        this.db=db; this.auth=auth; this.gradio=gradio; this.maxUploadBytes=maxUploadBytes;
        this.resultDirectory=Path.of(System.getProperty("java.io.tmpdir"), "atelier-virtual-tryon").toAbsolutePath().normalize();
    }

    public boolean available() { return gradio.configured(); }
    public String availabilityMessage() { return available() ? "OOTDiffusion is configured. Free Space capacity and queue times may vary." : "OOTDiffusion Space is not configured. No photo is uploaded until you consent and submit."; }

    public JobResponse generate(String authorization, UUID productId, UUID variantId, boolean consent, MultipartFile photo) {
        UUID userId=auth.subject(authorization);
        requireConfigured();
        if (!consent) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Consent is required before sending your photo to Hugging Face.");
        if (productId==null || photo==null || photo.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"A product and customer photo are required.");
        if (photo.getSize()>maxUploadBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Choose a JPEG or PNG photo under 5 MB.");
        if (db.queryForObject("SELECT count(*) FROM customer_users WHERE id=? AND enabled=true",Integer.class,userId)==0)
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Sign in again to use virtual try-on.");
        Integer recent=db.queryForObject("SELECT count(*) FROM virtual_tryon_jobs WHERE user_id=? AND created_at>now()-interval '10 minutes'",Integer.class,userId);
        if (recent!=null&&recent>=2) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"You have reached the try-on limit. Please try again later.");

        ImageInput image=validateImage(photo);
        CatalogGarment garment=loadGarment(productId,variantId);
        String ootCategory = resolveOotCategory(garment);
        if (ootCategory == null) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"This product is not in an OOTDiffusion-supported category (upper-body, lower-body, or dress).");
        CatalogImage catalogImage=downloadCatalogImage(garment.imageUrl());
        ImageInput validatedGarment=validateImage(catalogImage.bytes(),catalogImage.mime());
     UUID jobId = UUID.randomUUID();

Instant now = Instant.now();

db.update(
        """
        INSERT INTO virtual_tryon_jobs(
            id,
            user_id,
            product_id,
            variant_id,
            status,
            status_message,
            consented_at,
            created_at,
            updated_at,
            expires_at
        )
        VALUES (
            ?, ?, ?, ?,
            'QUEUED',
            'Waiting for the Hugging Face Space queue',
            ?, ?, ?, ?
        )
        """,
        jobId,
        userId,
        productId,
        variantId,
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now.plus(Duration.ofHours(1)))
);
        try {
            workers.execute(() -> process(jobId,image.bytes(),image.mime(),validatedGarment.bytes(),validatedGarment.mime(),garment.name(),ootCategory));
        } catch (RuntimeException e) {
            db.update("UPDATE virtual_tryon_jobs SET status='FAILED',status_message='The try-on queue is full. Please retry shortly.',updated_at=now() WHERE id=?",jobId);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"The try-on queue is full. Please retry shortly.");
        }
        return new JobResponse(jobId,"QUEUED","Photo sent to the selected Space after your consent. Preview results are illustrative and do not guarantee fit, size, color, or appearance.",null);
    }

    public JobResponse status(String authorization, UUID jobId) {
        UUID userId=auth.subject(authorization); JobRow row=findOwned(userId,jobId);
        if (!row.expiresAt().isAfter(Instant.now()) && !"EXPIRED".equals(row.status())) { expire(row); return new JobResponse(jobId,"EXPIRED","Temporary preview cleaned up.",null); }
        if ("COMPLETED".equals(row.status()) && (row.resultPath()==null || !Files.isRegularFile(Path.of(row.resultPath())))) {
            db.update("UPDATE virtual_tryon_jobs SET status='EXPIRED',status_message='The temporary preview has expired. Generate a new one to continue.',result_path=NULL,updated_at=now() WHERE id=? AND user_id=?",jobId,userId);
            return new JobResponse(jobId,"EXPIRED","The temporary preview has expired. Generate a new one to continue.",null);
        }
        return new JobResponse(row.id(),row.status(),Objects.toString(row.message(),""),"COMPLETED".equals(row.status())?"/api/v1/virtual-tryon/"+jobId+"/result":null);
    }

    public ResultFile result(String authorization, UUID jobId) {
        UUID userId=auth.subject(authorization); JobRow row=findOwned(userId,jobId);
        if (!"COMPLETED".equals(row.status()) || row.resultPath()==null) throw new ResponseStatusException(HttpStatus.CONFLICT,"Try-on result is not ready.");
        Path path=Path.of(row.resultPath()).toAbsolutePath().normalize();
        if (!path.startsWith(resultDirectory) || !Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.GONE,"This private preview has expired.");
        try { return new ResultFile(path,Files.probeContentType(path)); }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.GONE,"This private preview is no longer available."); }
    }

    @Scheduled(fixedDelay=60_000)
    public void cleanupExpiredResults() {
        try {
            Files.createDirectories(resultDirectory);
            List<Map<String,Object>> expired=db.queryForList("SELECT id,result_path FROM virtual_tryon_jobs WHERE (expires_at<now() AND status IN ('QUEUED','PROCESSING','COMPLETED')) OR (created_at<now()-interval '7 days')");
            for (Map<String,Object> row:expired) {
                Object path=row.get("result_path"); if (path!=null) deleteOwnedPath(path.toString());
                db.update("UPDATE virtual_tryon_jobs SET status='EXPIRED',status_message='Temporary preview cleaned up.',result_path=NULL,updated_at=now() WHERE id=?",row.get("id"));
            }
            db.update("DELETE FROM virtual_tryon_jobs WHERE created_at<now()-interval '7 days'");
        } catch (Exception ignored) { /* Cleanup is retried on the next scheduled run. */ }
    }

 
private void process(
        UUID id,
        byte[] person,
        String personMime,
        byte[] garment,
        String garmentMime,
        String garmentName,
        String ootCategory) {

    Path result = null;

    try {
        int changed = db.update(
                """
                UPDATE virtual_tryon_jobs
                SET status = 'PROCESSING',
                    status_message = 'The Space is preparing the garment preview.',
                    updated_at = now()
                WHERE id = ?
                  AND status = 'QUEUED'
                """,
                id
        );

        if (changed == 0) {
            return;
        }

        byte[] generated = gradio.generate(
                person,
                personMime,
                garment,
                garmentMime,
                ootCategory
        );

        if (generated == null || generated.length == 0) {
            throw new IllegalStateException(
                    "The try-on provider returned an empty image."
            );
        }

        Files.createDirectories(resultDirectory);

        result = Files.createTempFile(
                resultDirectory,
                "atelier-tryon-",
                isPng(generated) ? ".png" : ".jpg"
        );

        Files.write(
                result,
                generated,
                StandardOpenOption.TRUNCATE_EXISTING
        );

        int completed = db.update(
                """
                UPDATE virtual_tryon_jobs
                SET status = 'COMPLETED',
                    status_message = 'Your illustrative preview is ready. It is not a sizing or fit guarantee.',
                    result_path = ?,
                    expires_at = now() + interval '30 minutes',
                    updated_at = now()
                WHERE id = ?
                  AND status = 'PROCESSING'
                  AND expires_at > now()
                """,
                result.toString(),
                id
        );

        if (completed == 0) {
            deleteOwnedPath(result.toString());
        }

   
    } catch (Exception ex) {

        // Remove any partially generated image.
        if (result != null) {
            deleteOwnedPath(result.toString());
        }

        String errorId = UUID.randomUUID().toString();

        // Find the deepest underlying exception.
        Throwable root = ex;
        Set<Throwable> seen =
                Collections.newSetFromMap(new IdentityHashMap<>());

        while (root.getCause() != null && seen.add(root)) {
            root = root.getCause();
        }

        String exceptionType =
                ex.getClass().getSimpleName();

        String rootType =
                root.getClass().getSimpleName();

        // Log the complete exception chain server-side only.
        log.error(
                "Virtual try-on failed. jobId={}, errorId={}, " +
                "exceptionType={}, rootType={}, rootMessage={}",
                id,
                errorId,
                exceptionType,
                rootType,
                root.getMessage(),
                ex
        );

        String message;

        if (ex instanceof ProviderException) {
            message =
                    "The AI try-on provider could not complete " +
                    "the request. Please retry later. Reference: " +
                    errorId;

        } else if (ex instanceof org.springframework.dao.DataAccessException) {
            message =
                    "A database error occurred while processing " +
                    "your try-on. Reference: " + errorId;

        } else if (ex instanceof java.io.IOException) {
            message =
                    "An image processing or file operation failed. " +
                    "Reference: " + errorId;

        } else {
            message =
                    "The try-on job encountered an internal error. " +
                    "Reference: " + errorId;
        }

        try {
            int updated = db.update(
                    """
                    UPDATE virtual_tryon_jobs
                    SET status = 'FAILED',
                        status_message = ?,
                        result_path = NULL,
                        updated_at = now()
                    WHERE id = ?
                      AND status IN ('QUEUED', 'PROCESSING')
                    """,
                    message,
                    id
            );

            if (updated == 0) {
                log.warn(
                        "Try-on failure was not persisted because " +
                        "the job was no longer QUEUED or PROCESSING. " +
                        "jobId={}, errorId={}",
                        id,
                        errorId
                );
            }

        } catch (Exception dbEx) {
            log.error(
                    "Could not persist try-on failure. " +
                    "jobId={}, errorId={}",
                    id,
                    errorId,
                    dbEx
            );
        }
    }
}

 
private CatalogGarment loadGarment(UUID productId, UUID variantId) {

    if (variantId != null) {
        Integer variantCount = db.queryForObject(
                """
                SELECT count(*)
                FROM product_variants
                WHERE id = ?
                  AND product_id = ?
                  AND active = true
                """,
                Integer.class,
                variantId,
                productId
        );

        if (variantCount == null || variantCount == 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Selected variant does not belong to this product."
            );
        }
    }

    List<CatalogGarment> garments = db.query(
            """
            SELECT id, name, category,
                   COALESCE(
                       (SELECT image_url
                        FROM product_variants
                        WHERE id = ?
                          AND product_id = products.id
                          AND active = true),
                       image_url,
                       (SELECT image_url
                        FROM product_variants
                        WHERE product_id = products.id
                          AND active = true
                          AND image_url IS NOT NULL
                        ORDER BY created_at
                        LIMIT 1)
                   ) AS garment_image
            FROM products
            WHERE id = ?
              AND active = true
            """,
            (rs, rowNum) -> new CatalogGarment(
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("category"),
                    rs.getString("garment_image")
            ),
            variantId,
            productId
    );

    if (garments.isEmpty()) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Product not found or inactive."
        );
    }

    CatalogGarment garment = garments.get(0);

    if (garment.imageUrl() == null ||
            garment.imageUrl().isBlank()) {
        throw new ResponseStatusException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "This product does not have a usable catalog image."
        );
    }

    return garment;
}

    /** Maps the catalog product to the exact category values expected by OOTDiffusion. */
    private String resolveOotCategory(CatalogGarment garment) {
        if (garment == null || garment.imageUrl() == null || garment.imageUrl().isBlank()) {
            return null;
        }

        String name = normalizeCatalogText(Objects.toString(garment.name(), ""));
        String category = normalizeCatalogText(Objects.toString(garment.category(), ""));
        String text = " " + name + " " + category + " ";

        // Full-body/dress classification must run before lower/upper checks.
        if (containsCatalogTerm(text, "dress", "gown", "jumpsuit", "romper", "one piece",
                "saree", "sari", "maxi dress", "bodycon")) {
            return "Dress";
        }
        if (containsCatalogTerm(text, "lower body", "trouser", "trousers", "pants", "pant",
                "jeans", "jean", "denim", "skirt", "shorts", "leggings", "legging",
                "jogger", "bottom", "bottoms")) {
            return "Lower-body";
        }
        if (containsCatalogTerm(text, "upper body", "shirt", "t shirt", "tshirt", "tee",
                "top", "blouse", "sweater", "knit", "jacket", "coat", "blazer",
                "cardigan", "hoodie", "pullover", "kurta", "sweatshirt")) {
            return "Upper-body";
        }
        return null;
    }

    private String normalizeCatalogText(String value) {
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    private boolean containsCatalogTerm(String text, String... terms) {
        for (String term : terms) {
            String normalized = normalizeCatalogText(term);
            if ((" " + text + " ").contains(" " + normalized + " ")) {
                return true;
            }
        }
        return false;
    }

    private CatalogImage downloadCatalogImage(String imageUrl) {
        try {
            URI uri=URI.create(imageUrl); String host=uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host==null || uri.getUserInfo()!=null || (uri.getPort()!=-1&&uri.getPort()!=443)) throw new Exception();
            for (InetAddress address:InetAddress.getAllByName(host)) {
                if (address.isAnyLocalAddress()||address.isLoopbackAddress()||address.isLinkLocalAddress()||address.isSiteLocalAddress()||address.isMulticastAddress()) throw new Exception();
            }
            HttpRequest request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).header("Accept","image/jpeg,image/png").GET().build();
            HttpResponse<InputStream> response=imageClient.send(request,HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode()!=200) { response.body().close(); throw new Exception(); }
            String mime=response.headers().firstValue("content-type").orElse("").split(";",2)[0].trim().toLowerCase(Locale.ROOT);
            if (!Set.of("image/jpeg","image/png").contains(mime)) { response.body().close(); throw new Exception(); }
            try (InputStream in=response.body()) { byte[] bytes=in.readNBytes((int)maxUploadBytes+1); if (bytes.length>maxUploadBytes) throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Catalog image exceeds the 5 MB try-on limit."); return new CatalogImage(bytes,mime); }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"The selected product image could not be securely retrieved for try-on."); }
    }

    private ImageInput validateImage(MultipartFile file) {
        try { return validateImage(file.getBytes(),file.getContentType()); }
        catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Could not read the selected photo."); }
    }

    private ImageInput validateImage(byte[] bytes,String suppliedMime) {
        if (bytes==null||bytes.length==0||bytes.length>maxUploadBytes) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Choose a JPEG or PNG photo under 5 MB.");
        try (ImageInputStream stream=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (stream==null) throw new Exception();
            Iterator<ImageReader> readers=ImageIO.getImageReaders(stream); if (!readers.hasNext()) throw new Exception();
            ImageReader reader=readers.next();
            try {
                reader.setInput(stream,true,true);
                int width=reader.getWidth(0),height=reader.getHeight(0);
                if (width<256||height<256||width>8000||height>8000||(long)width*height>MAX_PIXELS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Photo dimensions must be at least 256×256 and no more than 16 megapixels.");
                String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                String actual="jpeg".equals(format)||"jpg".equals(format)?"image/jpeg":"png".equals(format)?"image/png":"";
                if (actual.isBlank() || suppliedMime==null || !("application/octet-stream".equalsIgnoreCase(suppliedMime)||actual.equalsIgnoreCase(suppliedMime))) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,"Only genuine JPEG and PNG photos are supported.");
                if (reader.read(0)==null) throw new Exception();
                return new ImageInput(bytes,actual);
            } finally { reader.dispose(); }
        } catch (ResponseStatusException e) { throw e; }
        catch (Exception e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"The uploaded file is not a valid readable JPEG or PNG image."); }
    }

    private void requireConfigured() { if (!available()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Configure HF_TOKEN (and optionally HF_SPACE_ID) on the backend to enable virtual try-on. OOTDIFFUSION_SPACE_URL is not read by this service."); }
   
private JobRow findOwned(UUID userId, UUID jobId) {

    List<JobRow> rows = db.query(
            """
            SELECT id, status, status_message,
                   result_path, expires_at
            FROM virtual_tryon_jobs
            WHERE id = ?
              AND user_id = ?
            """,
            (rs, rowNum) -> new JobRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("status"),
                    rs.getString("status_message"),
                    rs.getString("result_path"),
                    rs.getTimestamp("expires_at").toInstant()
            ),
            jobId,
            userId
    );

    if (rows.isEmpty()) {
        throw new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Try-on job was not found for this account."
        );
    }

    return rows.get(0);
}
    private void expire(JobRow row) { if(row.resultPath()!=null)deleteOwnedPath(row.resultPath()); db.update("UPDATE virtual_tryon_jobs SET status='EXPIRED',status_message='Temporary preview cleaned up.',result_path=NULL,updated_at=now() WHERE id=?",row.id()); }
    private void deleteOwnedPath(String value) { try { Path path=Path.of(value).toAbsolutePath().normalize(); if(path.startsWith(resultDirectory))Files.deleteIfExists(path); } catch(Exception ignored){} }
    private static String safeDescription(String name) { String cleaned=name==null?"garment":name.replaceAll("[^\\p{L}\\p{N} ,.'-]"," ").replaceAll("\\s+"," ").trim(); return cleaned.length()>100?cleaned.substring(0,100):cleaned; }

    public record JobResponse(UUID jobId,String status,String message,String resultUrl) { }
    public record ResultFile(Path path,String contentType) { }
    private record CatalogGarment(UUID id,String name,String category,String imageUrl) { }
    private record CatalogImage(byte[] bytes,String mime) { }
    private record ImageInput(byte[] bytes,String mime) { }
    private record JobRow(UUID id,String status,String message,String resultPath,Instant expiresAt) { }
    private static boolean isPng(byte[] bytes) { return bytes.length>8&&(bytes[0]&255)==137&&bytes[1]==80&&bytes[2]==78&&bytes[3]==71; }
}

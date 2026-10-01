package com.fashion.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ProductAssetStorage {
  @Value("${atelier.assets.provider:local}") private String provider;
  @Value("${atelier.assets.directory:./data/assets}") private String directory;
  @Value("${atelier.assets.public-base-url:http://localhost:8080/assets}") private String publicBaseUrl;
  @Value("${atelier.assets.s3.endpoint:}") private String s3Endpoint;
  @Value("${atelier.assets.s3.bucket:}") private String s3Bucket;
  @Value("${atelier.assets.s3.region:us-east-1}") private String s3Region;
  @Value("${atelier.assets.s3.access-key:}") private String s3AccessKey;
  @Value("${atelier.assets.s3.secret-key:}") private String s3SecretKey;
  @Value("${atelier.assets.s3.public-base-url:}") private String s3PublicBaseUrl;
  private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

  public String save(UUID productId, String objectName, byte[] bytes, String contentType) {
    String key = "products/" + productId + "/" + objectName;
    if ("s3".equalsIgnoreCase(provider)) return saveS3(key, bytes, contentType);
    if (!"local".equalsIgnoreCase(provider)) throw new IllegalStateException("Unsupported asset storage provider");
    try {
      Path root = Path.of(directory).toAbsolutePath().normalize();
      Path destination = root.resolve(key).normalize();
      if (!destination.startsWith(root)) throw new IllegalArgumentException("Invalid asset path");
      Files.createDirectories(destination.getParent());
      Path temporary = Files.createTempFile(destination.getParent(), "upload-", ".tmp");
      try {
        Files.write(temporary, bytes);
        Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
      } finally { Files.deleteIfExists(temporary); }
      return trimSlash(publicBaseUrl) + "/" + key;
    } catch (Exception exception) { throw new IllegalStateException("Could not persist uploaded product model", exception); }
  }

  private String saveS3(String key, byte[] bytes, String contentType) {
    if (s3Endpoint.isBlank() || s3Bucket.isBlank() || s3AccessKey.isBlank() || s3SecretKey.isBlank())
      throw new IllegalStateException("S3 storage requires endpoint, bucket, access key, and secret key configuration");
    try {
      URI endpoint = URI.create(trimSlash(s3Endpoint));
      String encodedKey = encodePath(key);
      String path = (endpoint.getRawPath() == null ? "" : endpoint.getRawPath().replaceAll("/$", "")) + "/" + s3Bucket + "/" + encodedKey;
      URI target = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
      String host = target.getRawAuthority();
      String payloadHash = sha256(bytes);
      String amzDate = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC).format(java.time.Instant.now());
      String date = amzDate.substring(0, 8);
      String canonicalHeaders = "host:" + host + "\n" + "x-amz-content-sha256:" + payloadHash + "\n" + "x-amz-date:" + amzDate + "\n";
      String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
      String canonicalRequest = "PUT\n" + target.getRawPath() + "\n\n" + canonicalHeaders + signedHeaders + "\n" + payloadHash;
      String scope = date + "/" + s3Region + "/s3/aws4_request";
      String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + sha256(canonicalRequest.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      byte[] signingKey = hmac(hmac(hmac(hmac(("AWS4" + s3SecretKey).getBytes(java.nio.charset.StandardCharsets.UTF_8), date), s3Region), "s3"), "aws4_request");
      String signature = HexFormat.of().formatHex(hmac(signingKey, toSign));
      String authorization = "AWS4-HMAC-SHA256 Credential=" + s3AccessKey + "/" + scope + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
      HttpRequest request = HttpRequest.newBuilder(target).header("Content-Type", contentType)
          .header("x-amz-content-sha256", payloadHash).header("x-amz-date", amzDate)
          .header("Authorization", authorization).PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
      HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Object storage rejected the upload (HTTP " + response.statusCode() + ")");
      String publicRoot = s3PublicBaseUrl.isBlank() ? trimSlash(s3Endpoint) + "/" + s3Bucket : trimSlash(s3PublicBaseUrl);
      return publicRoot + "/" + encodedKey;
    } catch (Exception exception) { throw new IllegalStateException("Could not upload product model to S3-compatible storage", exception); }
  }

  private static String encodePath(String value) {
    return java.util.Arrays.stream(value.split("/", -1)).map(part -> java.net.URLEncoder.encode(part, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")).collect(java.util.stream.Collectors.joining("/"));
  }
  private static String trimSlash(String value) { return value.replaceAll("/+$", ""); }
  private static String sha256(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
  private static byte[] hmac(byte[] key, String value) throws Exception { Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
}

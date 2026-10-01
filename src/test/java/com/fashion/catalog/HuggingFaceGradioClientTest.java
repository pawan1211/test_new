package com.fashion.catalog;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class HuggingFaceGradioClientTest {
//     private static final byte[] RESULT={(byte)137,80,78,71,13,10,26,10,1,2};

//     @Test void discoversActualParameterSchemaUploadsQueuesAndDownloadsGeneratedImage() throws Exception {
//         HttpClient http=mock(HttpClient.class);
//         when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenAnswer(invocation->{
//             HttpRequest request=invocation.getArgument(0); String path=request.uri().getPath(); int status=200; Object body;
         
//          if (path.endsWith("/gradio_api/info")) {
//     body = """
//         {"named_endpoints":{"/tryon":{"parameters":[{"parameter_name":"dict"},{"parameter_name":"garm_img"},{"parameter_name":"garment_des"},{"parameter_name":"is_checked"},{"parameter_name":"is_checked_crop"},{"parameter_name":"denoise_steps"},{"parameter_name":"seed"}]}}}
//         """.getBytes();
// }
         
//             else if(path.endsWith("/gradio_api/upload")) body="[\"/tmp/gradio/uploaded.png\"]".getBytes();
//             else if(path.endsWith("/gradio_api/call/tryon")) body="{\"event_id\":\"queue-42\"}".getBytes();
//             else if(path.endsWith("/gradio_api/call/tryon/queue-42")) body=new ByteArrayInputStream("event: complete\ndata: [{\"path\":\"/tmp/gradio/result.png\",\"mime_type\":\"image/png\"}]\n\n".getBytes());
//             else if(path.contains("/gradio_api/file=")) body=RESULT;
//             else {status=404;body=new byte[0];}
//             HttpResponse response=mock(HttpResponse.class);
//             when(response.statusCode()).thenReturn(status); when(response.body()).thenReturn(body);
//             when(response.headers()).thenReturn(HttpHeaders.of(Map.of("content-type",List.of(path.contains("file=")?"image/png":"application/json")),(a,b)->true));
//             when(response.uri()).thenReturn(request.uri()); when(response.request()).thenReturn(request);
//             return response;
//         });
//         HuggingFaceGradioClient client=new HuggingFaceGradioClient(URI.create("https://test-space.hf.space/"),"hf-test",new ObjectMapper(),http);

//         byte[] output=client.generate(new byte[]{1,2,3},"image/jpeg",new byte[]{4,5,6},"image/png","upper body garment, shirt");

//         assertArrayEquals(RESULT,output);
//         verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
//     }

//     @Test void rejectsAChangedLiveSpaceSchemaBeforeUploadingPhotos() throws Exception {
//         HttpClient http=mock(HttpClient.class);
//         HttpResponse<byte[]> response=mock(HttpResponse.class);
//         when(response.statusCode()).thenReturn(200);
//         when(response.body()).thenReturn("{\"named_endpoints\":{\"/tryon\":{\"parameters\":[{\"parameter_name\":\"person\"}]}}}".getBytes());
//         when(response.headers()).thenReturn(HttpHeaders.of(Map.of(),(a,b)->true));
//         when(http.send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class))).thenReturn(response);
//         HuggingFaceGradioClient client=new HuggingFaceGradioClient(URI.create("https://test-space.hf.space/"),"hf-test",new ObjectMapper(),http);

//         HuggingFaceGradioClient.ProviderException error=assertThrows(HuggingFaceGradioClient.ProviderException.class,
//                 ()->client.generate(new byte[]{1},"image/jpeg",new byte[]{2},"image/png","garment"));

//         assertTrue(error.getMessage().contains("schema"));
//         verify(http,times(1)).send(any(HttpRequest.class),any(HttpResponse.BodyHandler.class));
//     }
}

package com.fashion.catalog;

import com.fashion.security.CustomerAuthController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class VirtualTryOnServiceTest {
    private final JdbcTemplate db=mock(JdbcTemplate.class);
    private final CustomerAuthController auth=mock(CustomerAuthController.class);
    private final HuggingFaceGradioClient provider=mock(HuggingFaceGradioClient.class);
    private final VirtualTryOnService service=new VirtualTryOnService(db,auth,provider,5*1024*1024);

    @Test void rejectsUnauthenticatedRequestBeforeAcceptingPhoto() {
        when(auth.subject(null)).thenThrow(new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Unauthorized"));
        assertEquals(HttpStatus.UNAUTHORIZED,assertThrows(ResponseStatusException.class,
                ()->service.generate(null,UUID.randomUUID(),null,true,new MockMultipartFile("photo","p.png","image/png",new byte[]{1}))).getStatusCode());
        verifyNoInteractions(provider);
    }

    @Test void reportsUnavailableProviderWithoutProcessingAnImage() {
        when(auth.subject("Bearer customer")).thenReturn(UUID.randomUUID()); when(provider.configured()).thenReturn(false);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,assertThrows(ResponseStatusException.class,
                ()->service.generate("Bearer customer",UUID.randomUUID(),null,true,null)).getStatusCode());
    }

    @Test void requiresExplicitConsentAndARealPhoto() {
        when(auth.subject("Bearer customer")).thenReturn(UUID.randomUUID()); when(provider.configured()).thenReturn(true);
        ResponseStatusException noConsent=assertThrows(ResponseStatusException.class,
                ()->service.generate("Bearer customer",UUID.randomUUID(),null,false,null));
        assertEquals(HttpStatus.BAD_REQUEST,noConsent.getStatusCode());
        ResponseStatusException noPhoto=assertThrows(ResponseStatusException.class,
                ()->service.generate("Bearer customer",UUID.randomUUID(),null,true,null));
        assertEquals(HttpStatus.BAD_REQUEST,noPhoto.getStatusCode());
    }
}

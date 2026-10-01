package com.fashion.commerce;

import com.fashion.security.CustomerAuthController;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/commerce/returns")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class ReturnRefundAdminController {
 private final JdbcTemplate db; private final CustomerAuthController auth; private final RestClient http=RestClient.create();
 @Value("${providers.cashfree.base-url:https://sandbox.cashfree.com/pg}") private String cfUrl;
 @Value("${providers.cashfree.app-id:}") private String appId;
 @Value("${providers.cashfree.secret-key:}") private String secret;
 @Value("${providers.cashfree.api-version:2023-08-01}") private String version;
 public ReturnRefundAdminController(JdbcTemplate db,CustomerAuthController auth){this.db=db;this.auth=auth;}
 private void admin(String bearer){if(bearer==null||!auth.isAdmin(bearer))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin role required");}
 private void configured(){if(appId.isBlank()||secret.isBlank())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Cashfree credentials are not configured");}

 @PostMapping("/{id}/refund")
 public Map<String,Object> refund(@RequestHeader("Authorization") String bearer,@PathVariable UUID id){
   admin(bearer);configured();
   Map<String,Object> r;
   try {r=db.queryForMap("SELECT rr.id,rr.order_id,rr.status,rr.refund_amount,rr.cashfree_refund_id,co.order_number,co.payment_status FROM return_requests rr JOIN customer_orders co ON co.id=rr.order_id WHERE rr.id=?",id);}
   catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Return request not found");}
   if(!"APPROVED".equals(r.get("status")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Approve the return before initiating refund");
   if(!"PAID".equalsIgnoreCase(Objects.toString(r.get("payment_status"),"")))throw new ResponseStatusException(HttpStatus.CONFLICT,"Order is not paid");
   if(r.get("cashfree_refund_id")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"Refund already initiated");
   String cfOrder="luxe_"+r.get("order_id").toString().replace("-","");
   String refundId="ret_"+id.toString().replace("-","");
   Map<String,Object> payload=Map.of("refund_amount",((Number)r.get("refund_amount")).doubleValue(),"refund_id",refundId,"refund_note","Approved return "+id);
   Map response;
   try{
    response=http.post().uri(cfUrl+"/orders/"+cfOrder+"/refunds")
      .header("x-client-id",appId).header("x-client-secret",secret).header("x-api-version",version)
      .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().body(Map.class);
   }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Cashfree refund request failed; check Cashfree dashboard before retrying");}
   String providerRefund=Objects.toString(response.get("cf_refund_id"),refundId);
   db.update("UPDATE return_requests SET status='REFUND_PENDING',cashfree_refund_id=?,refund_reference=?,refund_initiated_at=now(),updated_at=now() WHERE id=? AND status='APPROVED'",providerRefund,providerRefund,id);
   Map<String,Object> out=new LinkedHashMap<>();out.put("returnId",id);out.put("status","REFUND_PENDING");out.put("cashfreeRefundId",providerRefund);out.put("providerStatus",response.get("refund_status"));return out;
 }

 @PostMapping("/{id}/refund/verify")
 public Map<String,Object> verify(@RequestHeader("Authorization") String bearer,@PathVariable UUID id){
  admin(bearer);configured();Map<String,Object> r;
  try{r=db.queryForMap("SELECT rr.order_id,rr.cashfree_refund_id,co.order_number FROM return_requests rr JOIN customer_orders co ON co.id=rr.order_id WHERE rr.id=?",id);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Return request not found");}
  String refund=Objects.toString(r.get("cashfree_refund_id"),"");
  if(refund.isBlank())throw new ResponseStatusException(HttpStatus.CONFLICT,"No Cashfree refund has been initiated");
  String cfOrder="luxe_"+r.get("order_id").toString().replace("-","");
  Map response;
  try{response=http.get().uri(cfUrl+"/orders/"+cfOrder+"/refunds/"+refund).header("x-client-id",appId).header("x-client-secret",secret).header("x-api-version",version).retrieve().body(Map.class);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Could not verify refund with Cashfree");}
  String status=Objects.toString(response.get("refund_status"),"").toUpperCase(Locale.ROOT);
  if(Set.of("SUCCESS","SUCCESSFUL","PROCESSED").contains(status))
    db.update("UPDATE return_requests SET status='REFUNDED',refund_reference=?,updated_at=now() WHERE id=?",refund,id);
  else if(Set.of("FAILED","CANCELLED").contains(status))
    db.update("UPDATE return_requests SET status='REFUND_FAILED',admin_note=COALESCE(admin_note,'') || ?,updated_at=now() WHERE id=?", " | Cashfree refund status: "+status,id);
  Map<String,Object> out=new LinkedHashMap<>();out.put("returnId",id);out.put("providerStatus",status);out.put("returnStatus",db.queryForObject("SELECT status FROM return_requests WHERE id=?",String.class,id));return out;
 }
}

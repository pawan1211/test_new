package com.fashion.commerce;

import com.fashion.security.CustomerAuthController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/commerce")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class ReturnRequestController {
 private final JdbcTemplate db; private final CustomerAuthController auth;
 public ReturnRequestController(JdbcTemplate db, CustomerAuthController auth){this.db=db;this.auth=auth;}

 @PostMapping("/orders/{orderId}/return-request")
 @Transactional
 public Map<String,Object> request(@RequestHeader("Authorization") String authorization,
   @PathVariable UUID orderId,@RequestBody Request body){
   UUID userId=auth.subject(authorization);
   if(body==null||body.reason==null||body.reason.isBlank()||body.reason.length()>500)
     throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Reason is required (maximum 500 characters)");
   Map<String,Object> order;
   try { order=db.queryForMap("SELECT id,user_id,payment_status,order_status,total_amount,created_at FROM customer_orders WHERE id=? AND user_id=?",orderId,userId); }
   catch(Exception e){throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Order not found for this account");}
   if(!"PAID".equalsIgnoreCase(Objects.toString(order.get("payment_status"),"")))
     throw new ResponseStatusException(HttpStatus.CONFLICT,"Only paid orders are eligible for return/refund");
   if(!Set.of("DELIVERED").contains(Objects.toString(order.get("order_status"),"").toUpperCase(Locale.ROOT)))
     throw new ResponseStatusException(HttpStatus.CONFLICT,"Only delivered orders can be returned");
   Integer exists=db.queryForObject("SELECT count(*) FROM return_requests WHERE order_id=? AND status NOT IN ('REJECTED','CANCELLED')",Integer.class,orderId);
   if(exists!=null&&exists>0)throw new ResponseStatusException(HttpStatus.CONFLICT,"A return request already exists for this order");
   UUID id=UUID.randomUUID();
   db.update("INSERT INTO return_requests(id,order_id,user_id,reason,status,refund_amount,created_at,updated_at) VALUES(?,?,?,?,'REQUESTED',?,now(),now())",
       id,orderId,userId,body.reason.trim(),order.get("total_amount"));
   return db.queryForMap("SELECT id,order_id AS \"orderId\",status,reason,refund_amount AS \"refundAmount\",created_at AS \"createdAt\" FROM return_requests WHERE id=?",id);
 }
 @GetMapping("/my/returns")
 public List<Map<String,Object>> mine(@RequestHeader("Authorization") String authorization){
   UUID userId=auth.subject(authorization);
   return db.queryForList("SELECT id,order_id AS \"orderId\",reason,status,refund_amount AS \"refundAmount\",refund_reference AS \"refundReference\",admin_note AS \"adminNote\",created_at AS \"createdAt\",updated_at AS \"updatedAt\" FROM return_requests WHERE user_id=? ORDER BY created_at DESC",userId);
 }
 public static class Request { public String reason; }
}

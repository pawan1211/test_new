package com.fashion.commerce;

import com.fashion.security.CustomerAuthController;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin/commerce")
@CrossOrigin(origins="${app.cors-origin:http://localhost:3000}")
public class AdminOperationsController {
 private final JdbcTemplate db; private final CustomerAuthController auth;
 public AdminOperationsController(JdbcTemplate db, CustomerAuthController auth){this.db=db;this.auth=auth;}
 private void admin(String token){if(token==null||!auth.isAdmin(token))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Admin role required");}
 @GetMapping("/orders") public List<Map<String,Object>> orders(@RequestHeader(value="Authorization",required=false) String token){admin(token);return db.queryForList("SELECT id,order_number AS \"orderNumber\",customer_name AS \"customerName\",email,phone,total_amount AS total,payment_status AS \"paymentStatus\",order_status AS status,created_at AS \"createdAt\" FROM customer_orders ORDER BY created_at DESC LIMIT 500");}
 @GetMapping("/returns") public List<Map<String,Object>> returns(@RequestHeader(value="Authorization",required=false) String token){admin(token);return db.queryForList("SELECT id,order_id AS \"orderId\",user_id AS \"userId\",reason,status,refund_amount AS \"refundAmount\",refund_reference AS \"refundReference\",admin_note AS \"adminNote\",created_at AS \"createdAt\" FROM return_requests ORDER BY created_at DESC LIMIT 500");}
 @PatchMapping("/returns/{id}") public Map<String,Object> updateReturn(@RequestHeader(value="Authorization",required=false) String token,@PathVariable UUID id,@RequestBody ReturnUpdate r){admin(token);if(!Set.of("APPROVED","REJECTED","REFUND_PENDING","REFUNDED","RECEIVED").contains(r.status))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported return status");int n=db.update("UPDATE return_requests SET status=?,admin_note=?,refund_amount=COALESCE(?,refund_amount),refund_reference=COALESCE(?,refund_reference),updated_at=now() WHERE id=?",r.status,r.adminNote,r.refundAmount,r.refundReference,id);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Return request not found");return db.queryForMap("SELECT id,status,admin_note AS \"adminNote\",refund_amount AS \"refundAmount\",refund_reference AS \"refundReference\" FROM return_requests WHERE id=?",id);}
 @PatchMapping("/inventory/{variantId}") public Map<String,Object> inventory(@RequestHeader(value="Authorization",required=false) String token,@PathVariable UUID variantId,@RequestBody StockUpdate s){admin(token);if(s.stock==null||s.stock<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Stock must be zero or greater");int n=db.update("UPDATE product_variants SET stock_quantity=? WHERE id=?",s.stock,variantId);if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Variant not found");return db.queryForMap("SELECT id,sku,size,color,stock_quantity AS stock,active FROM product_variants WHERE id=?",variantId);}
 public static class ReturnUpdate {public String status,adminNote,refundReference;public java.math.BigDecimal refundAmount;}
 public static class StockUpdate {public Integer stock;}
}

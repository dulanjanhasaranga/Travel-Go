package com.travelgo.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.travelgo.dto.PayHereBillingRequest;
import com.travelgo.entity.*;
import com.travelgo.enums.PaymentType;
import com.travelgo.repository.ProviderCheckoutAttemptRepository;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service("payHere")
public class PayHereService {
    private final boolean enabled, simulated, stripeEnabled, usdApproved;
    private final String mode, merchantId, secret, appId, appSecret, publicOrigin;
    private final ProviderCheckoutAttemptService attempts;
    private final ProviderCheckoutAttemptRepository repository;
    private final PayHereMerchantClient merchant;
    private final Clock clock;
    public PayHereService(@Value("${travelgo.payhere.enabled:false}") boolean enabled,
            @Value("${travelgo.payments.simulated:true}") boolean simulated,
            @Value("${travelgo.stripe.enabled:false}") boolean stripeEnabled,
            @Value("${travelgo.payhere.usd-approved:false}") boolean usdApproved,
            @Value("${travelgo.payhere.mode:test}") String mode,
            @Value("${travelgo.payhere.merchant-id:}") String merchantId,
            @Value("${travelgo.payhere.merchant-secret:}") String secret,
            @Value("${travelgo.payhere.app-id:}") String appId,
            @Value("${travelgo.payhere.app-secret:}") String appSecret,
            @Value("${travelgo.public-base-url:http://localhost:8081}") String publicOrigin,
            ProviderCheckoutAttemptService attempts, ProviderCheckoutAttemptRepository repository,
            PayHereMerchantClient merchant, Clock clock) {
        this.enabled=enabled;this.simulated=simulated;this.stripeEnabled=stripeEnabled;this.usdApproved=usdApproved;
        this.mode=mode;this.merchantId=merchantId;this.secret=secret;this.appId=appId;this.appSecret=appSecret;
        this.publicOrigin=publicOrigin;this.attempts=attempts;this.repository=repository;this.merchant=merchant;this.clock=clock;
    }
    @PostConstruct public void validateConfiguration() {
        if(!enabled)return;
        if(simulated||stripeEnabled)throw new IllegalStateException("Enable exactly one online payment provider and turn off simulated payments.");
        if(!List.of("test","live").contains(mode)||merchantId==null||!merchantId.matches("[0-9]{5,20}")
                ||!credential(secret)||secret.length()<16||!credential(appId)||!credential(appSecret)||appSecret.length()<16||appId.contains(":"))
            throw new IllegalStateException("PayHere requires a valid mode, merchant credentials and Merchant API credentials.");
        if(!usdApproved)throw new IllegalStateException("Confirm PayHere USD acceptance before using the existing USD prices. No currency conversion is performed.");
        URI origin=origin();
        if(!"https".equals(origin.getScheme())&&("live".equals(mode)||!List.of("localhost","127.0.0.1","[::1]").contains(origin.getHost())))
            throw new IllegalStateException("PayHere requires HTTPS; loopback HTTP is allowed only for local test preparation.");
    }
    private boolean credential(String v){return v!=null&&v.length()>=16&&v.length()<=500&&!v.toLowerCase(Locale.ROOT).contains("placeholder")&&!v.toLowerCase(Locale.ROOT).contains("replace_")&&!v.chars().anyMatch(Character::isWhitespace)&&!v.chars().anyMatch(Character::isISOControl);}
    public boolean isConfigured(){return enabled&&!simulated&&!stripeEnabled;}
    public boolean isTestMode(){return "test".equals(mode);}
    public String getMode(){return mode;}
    private void configured(){if(!isConfigured())throw new IllegalStateException("PayHere checkout is unavailable.");validateConfiguration();}
    public String deploymentReference(){return "payhere-"+sha256(origin()+":"+merchantId+":"+mode).substring(0,32);}
    public record Checkout(String action, Map<String,String> fields){}
    public Checkout checkout(Booking b, PaymentType type, BigDecimal expected, LocalDateTime deadline, PayHereBillingRequest billing){
        configured();
        if(billing.getAmount()==null||billing.getAmount().compareTo(expected)!=0)throw new IllegalArgumentException("The amount changed. Refresh checkout.");
        ProviderCheckoutAttempt attempt=attempts.preparePayHere(b,type,expected,deadline,mode,deploymentReference());
        String order=orderId(attempt);String action=isTestMode()?"https://sandbox.payhere.lk/pay/checkout":"https://www.payhere.lk/pay/checkout";
        attempts.completed(attempt.getId(),order,action);
        Map<String,String> fields=new LinkedHashMap<>();
        fields.put("merchant_id",merchantId);fields.put("order_id",order);fields.put("currency","USD");fields.put("amount",money(expected));
        fields.put("return_url",origin()+"/customer/payments/checkout/"+b.getId()+"?success=true");
        fields.put("cancel_url",origin()+"/customer/payments/checkout/"+b.getId()+"?canceled=true");
        fields.put("notify_url",origin()+"/api/webhooks/payhere");fields.put("items","TravelGO booking #"+b.getId()+" - "+type.name().replace('_',' '));
        fields.put("first_name",contact(billing.getFirstName(),50));fields.put("last_name",contact(billing.getLastName(),50));
        fields.put("email",contact(billing.getEmail(),150));fields.put("phone",contact(billing.getPhone(),20));
        fields.put("address",contact(billing.getAddress(),200));fields.put("city",contact(billing.getCity(),100));fields.put("country",contact(billing.getCountry(),100));
        fields.put("hash",md5(merchantId+order+money(expected)+"USD"+md5(secret)));
        return new Checkout(action,Collections.unmodifiableMap(fields));
    }
    public record Notice(String orderId,String paymentId,BigDecimal amount,String currency,String status,String signature){}
    public Notice verifyNotification(Map<String,String> params){
        configured();String merchantIdReceived=params.get("merchant_id"),order=params.get("order_id"),payment=params.get("payment_id"),amount=params.get("payhere_amount"),currency=params.get("payhere_currency"),status=params.get("status_code"),signature=params.get("md5sig");
        if(!merchantId.equals(merchantIdReceived)||order==null||!order.matches("TGPH-[1-9][0-9]*-[a-f0-9]{24}")
                ||payment==null||!payment.matches("[0-9]{1,30}")||amount==null||!amount.matches("[0-9]{1,8}\\.[0-9]{2}")
                ||!"USD".equals(currency)||!List.of("2","0","-1","-2","-3").contains(status==null?"":status)
                ||signature==null||!signature.matches("[A-Fa-f0-9]{32}"))throw new IllegalArgumentException("Invalid PayHere notification.");
        String calculated=md5(merchantId+order+amount+currency+status+md5(secret));
        if(!MessageDigest.isEqual(calculated.getBytes(StandardCharsets.US_ASCII),signature.toUpperCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Invalid PayHere notification signature.");
        return new Notice(order,payment,new BigDecimal(amount),currency,status,signature.toUpperCase(Locale.ROOT));
    }
    public ProviderCheckoutAttempt attempt(Notice notice){
        long id;try{id=Long.parseLong(notice.orderId().split("-")[1]);}catch(RuntimeException invalid){throw new IllegalArgumentException("Invalid PayHere checkout reference.");}
        var a=repository.findById(id).orElseThrow(()->new IllegalArgumentException("Unknown PayHere checkout."));
        if(!deploymentReference().equals(a.getDeploymentReference())||!mode.equals(a.getPaymentMode())
                ||!orderId(a).equals(notice.orderId())||!notice.orderId().equals(a.getSessionReference())
                ||notice.amount().compareTo(a.getAmount())!=0)throw new IllegalArgumentException("PayHere notification does not match its stored checkout.");
        return a;
    }
    /** The checksum excludes payment_id. Authenticate that ID independently through the Merchant API. */
    public void verifyReceivedPayment(Notice notice,ProviderCheckoutAttempt a){
        JsonNode payment=verifiedPayment(a,notice.paymentId());
        String status=payment.path("status").asText();Payment settled=a.getSettledPayment();
        boolean repeat=settled!=null&&"PayHere Checkout".equals(settled.getPaymentMethod())&&("PH-"+notice.paymentId()).equals(settled.getTransactionReference())
                &&settled.getAmount().compareTo(a.getAmount())==0;
        if(!"RECEIVED".equals(status)&&!(repeat&&List.of("REFUND REQUESTED","REFUND PROCESSING","REFUNDED").contains(status)))
            throw new IllegalStateException("PayHere payment needs reconciliation before it can be recorded.");
        // The API's wall-time date has no documented timezone. Apply the injected server clock instead.
        if(!repeat&&(clock.instant().getEpochSecond()>=a.getExpiresAt()
                ||clock.instant().getEpochSecond()>=a.getQuoteDeadline().atZone(clock.getZone()).toEpochSecond()))
            throw new IllegalStateException("The PayHere checkout deadline has passed. Staff reconciliation is required.");
    }
    public JsonNode verifiedPayment(ProviderCheckoutAttempt a,String paymentId){
        configured();if(!deploymentReference().equals(a.getDeploymentReference())||!mode.equals(a.getPaymentMode())||!orderId(a).equals(a.getSessionReference()))
            throw new IllegalStateException("This payment belongs to a different PayHere merchant, mode or deployment. Review the original provider account.");
        JsonNode result=merchant.payment(a.getSessionReference());JsonNode data=result.path("data");
        if(result.path("status").asInt(0)!=1||!data.isArray()||data.size()!=1)
            throw new IllegalStateException("PayHere returned missing or multiple payments for this order. Staff reconciliation is required.");
        JsonNode p=data.get(0);BigDecimal amount;
        try{amount=new BigDecimal(p.path("amount").asText());}catch(RuntimeException invalid){throw new IllegalStateException("PayHere returned an invalid payment amount.");}
        if(!paymentId.equals(p.path("payment_id").asText())||!a.getSessionReference().equals(p.path("order_id").asText())
                ||!"USD".equals(p.path("currency").asText())||amount.compareTo(a.getAmount())!=0)
            throw new IllegalStateException("PayHere payment evidence does not match its original order and amount.");
        return p;
    }
    public String orderId(ProviderCheckoutAttempt a){return "TGPH-"+a.getId()+"-"+sha256(deploymentReference()+":"+a.getId()).substring(0,24);}
    public static String money(BigDecimal amount){if(amount==null||amount.signum()<=0||amount.stripTrailingZeros().scale()>2||amount.compareTo(new BigDecimal("99999999.99"))>0)throw new IllegalArgumentException("A valid stored amount is required.");return amount.setScale(2).toPlainString();}
    private String contact(String value,int max){if(value==null||value.isBlank()||value.trim().length()>max||value.chars().anyMatch(Character::isISOControl))throw new IllegalArgumentException("Complete the billing contact details.");return value.trim();}
    private URI origin(){try{URI uri=URI.create(publicOrigin);if(uri.getHost()==null||!("https".equals(uri.getScheme())||"http".equals(uri.getScheme()))||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||!(uri.getPath().isEmpty()||"/".equals(uri.getPath())))throw new IllegalArgumentException();return URI.create(uri.getScheme()+"://"+uri.getRawAuthority());}catch(IllegalArgumentException invalid){throw new IllegalStateException("Configure a valid PayHere public origin.");}}
    private static String digest(String algorithm,String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(value.getBytes(StandardCharsets.UTF_8)));}catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException("Required message digest is unavailable.");}}
    public static String md5(String value){return digest("MD5",value).toUpperCase(Locale.ROOT);}
    public static String sha256(String value){return digest("SHA-256",value);}
}

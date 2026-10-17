package com.travelgo.service;
import com.travelgo.entity.Booking;
import com.travelgo.entity.Payment;
import org.springframework.stereotype.Service;
import java.util.List;
/** Retains the existing invoice URL while using the validated payment receipt format. */
@Service
public class InvoicePdfGenerator {
    private final PdfReceiptService receipts;
    public InvoicePdfGenerator(PdfReceiptService receipts){this.receipts=receipts;}
    public byte[] generateInvoice(Booking booking,Payment payment){return receipts.generateReceipt(booking,List.of(payment));}
}

package com.example.backend.controller;

import com.example.backend.service.InvoiceService;
import com.example.backend.service.PaymentService;
import com.example.backend.utils.GpaySecurityUtil;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.view.RedirectView;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@Tag("unit")
class PaymentControllerTest {

    @Test
    void rejectsUnsignedWebhooksBeforeTheyCanChangeAnInvoice() throws Exception {
        InvoiceService invoiceService = mock(InvoiceService.class);
        GpaySecurityUtil securityUtil = mock(GpaySecurityUtil.class);
        PaymentController controller = controller(invoiceService, securityUtil);

        MockHttpServletRequest request = jsonRequest("""
                {"merchant_order_id":"ORDER-1","gpay_trans_id":"TX-1","gpay_bill_id":"BILL-1",
                 "status":"SUCCESS","embed_data":"ORDER-1","user_payment_method":"CARD","signature":"invalid"}
                """);
        when(securityUtil.verifySignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(false);

        var response = controller.gpayWebhookAllInOne(request);

        assertEquals(401, response.getStatusCode().value());
        verify(invoiceService, never()).handlePaymentSuccess(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
        verify(invoiceService, never()).handlePaymentFailed(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void acceptsAVerifiedSuccessWebhookAndSettlesOnlyTheReferencedInvoice() throws Exception {
        InvoiceService invoiceService = mock(InvoiceService.class);
        GpaySecurityUtil securityUtil = mock(GpaySecurityUtil.class);
        PaymentController controller = controller(invoiceService, securityUtil);

        MockHttpServletRequest request = jsonRequest("""
                {"merchant_order_id":"ORDER-1","gpay_trans_id":"TX-1","gpay_bill_id":"BILL-1",
                 "status":"SUCCESS","embed_data":"ORDER-1","user_payment_method":"CARD","signature":"valid"}
                """);
        when(securityUtil.verifySignature(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString())).thenReturn(true);

        var response = controller.gpayWebhookAllInOne(request);

        assertEquals(200, response.getStatusCode().value());
        verify(invoiceService).handlePaymentSuccess("ORDER-1", "TX-1");
    }

    @Test
    void usesTheConfiguredFrontendForThePaymentCallback() {
        PaymentController controller = controller(mock(InvoiceService.class), mock(GpaySecurityUtil.class));
        ReflectionTestUtils.setField(controller, "frontendUrl", "https://shop.example.test");

        RedirectView redirect = controller.gpayCallback();

        assertEquals("https://shop.example.test", redirect.getUrl());
    }

    @Test
    void disabledPaymentsCannotCreateTransactionsOrSettleWebhooks() {
        InvoiceService invoices = mock(InvoiceService.class);
        GpaySecurityUtil verifier = mock(GpaySecurityUtil.class);
        PaymentService payments = mock(PaymentService.class);
        PaymentController controller = controller(invoices, verifier);
        ReflectionTestUtils.setField(controller, "paymentService", payments);
        ReflectionTestUtils.setField(controller, "paymentsEnabled", false);

        assertEquals(503, controller.getPaymentUrl("ORDER-1", null, null).getStatusCode().value());
        assertEquals(503, controller.getVirtualAccount("ORDER-1", null).getStatusCode().value());
        assertEquals(503, controller.gpayWebhookAllInOne(jsonRequest("{}")).getStatusCode().value());
        org.mockito.Mockito.verifyNoInteractions(invoices, verifier, payments);
    }

    private static PaymentController controller(InvoiceService invoiceService, GpaySecurityUtil securityUtil) {
        PaymentController controller = new PaymentController();
        ReflectionTestUtils.setField(controller, "paymentService", mock(PaymentService.class));
        ReflectionTestUtils.setField(controller, "invoiceService", invoiceService);
        ReflectionTestUtils.setField(controller, "securityUtil", securityUtil);
        ReflectionTestUtils.setField(controller, "gpayPublicKeyResource",
                new ByteArrayResource("not-used-by-mocked-verifier".getBytes(StandardCharsets.UTF_8)));
        return controller;
    }

    private static MockHttpServletRequest jsonRequest(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("application/json");
        request.setContent(json.getBytes(StandardCharsets.UTF_8));
        return request;
    }
}

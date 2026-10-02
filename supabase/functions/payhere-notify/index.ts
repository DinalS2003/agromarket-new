// AgroMarket: PayHere Server-to-Server Webhook Handler
// Securely verifies signature and transitions order states
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { getAdminClient } from "../_shared/supabaseClient.ts";
import md5 from "https://esm.sh/js-md5@0.8.3";

serve(async (req: Request) => {
  if (req.method !== "POST") {
    return new Response("Method not allowed", { status: 405 });
  }

  const supabase = getAdminClient();

  try {
    let params: Record<string, string> = {};
    const contentType = req.headers.get("content-type") || "";

    if (contentType.includes("application/x-www-form-urlencoded")) {
      const formData = await req.formData();
      for (const [key, value] of formData.entries()) {
        params[key] = String(value);
      }
    } else {
      try {
        params = await req.json();
      } catch (_e) {
        // Fallback for body parsing
        const text = await req.text();
        const searchParams = new URLSearchParams(text);
        for (const [k, v] of searchParams.entries()) {
          params[k] = v;
        }
      }
    }

    const merchantId = params["merchant_id"]?.trim();
    const orderId = params["order_id"]?.trim(); // payhere_order_id (e.g. AM-001001-1)
    const payhereAmount = params["payhere_amount"]?.trim();
    const payhereCurrency = params["payhere_currency"]?.trim();
    const statusCode = params["status_code"]?.trim();
    const md5sig = params["md5sig"]?.trim();
    const paymentId = params["payment_id"]?.trim();

    if (!merchantId || !orderId || !payhereAmount || !payhereCurrency || !statusCode || !md5sig) {
      console.warn("[payhere-notify] Missing required parameters received in payload keys:", Object.keys(params));
      return new Response("Missing parameters", { status: 400 });
    }

    const expectedMerchantId = Deno.env.get("PAYHERE_MERCHANT_ID")?.trim();
    const merchantSecret = Deno.env.get("PAYHERE_MERCHANT_SECRET")?.trim();

    if (!expectedMerchantId || !merchantSecret) {
      console.error("[payhere-notify] Missing PayHere environment configuration");
      return new Response("Configuration error", { status: 500 });
    }

    if (merchantId !== expectedMerchantId) {
      console.error(`[payhere-notify] Merchant ID mismatch: received ${merchantId}`);
      return new Response("Invalid merchant", { status: 400 });
    }

    // Verify md5sig:
    // md5sig = upper(md5(merchant_id + order_id + payhere_amount + payhere_currency + status_code + upper(md5(merchant_secret))))
    const hashedSecret = md5(merchantSecret).toUpperCase();
    const sigString = `${merchantId}${orderId}${payhereAmount}${payhereCurrency}${statusCode}${hashedSecret}`;
    const calculatedSig = md5(sigString).toUpperCase();
    const isSigValid = (calculatedSig === md5sig.toUpperCase());

    // Log notify attempt without revealing secrets
    console.log(
      `[payhere-notify] order_id=${orderId} payment_id=${paymentId} status_code=${statusCode} amount=${payhereAmount} ${payhereCurrency} sig_valid=${isSigValid}`
    );

    if (!isSigValid) {
      console.error(`[payhere-notify] Signature verification failed for order ${orderId}`);
      return new Response("Invalid signature", { status: 400 });
    }

    // Find payment record by payhere_order_id or order_id
    let { data: payment, error: payErr } = await supabase
      .from("payments")
      .select("id, order_id, amount, currency, status")
      .eq("payhere_order_id", orderId)
      .order("created_at", { ascending: false })
      .limit(1)
      .maybeSingle();

    if (!payment) {
      const { data: altPayment } = await supabase
        .from("payments")
        .select("id, order_id, amount, currency, status")
        .eq("order_id", orderId)
        .order("created_at", { ascending: false })
        .limit(1)
        .maybeSingle();
      payment = altPayment;
    }

    if (!payment) {
      console.error(`[payhere-notify] Payment record not found for orderId=${orderId}`);
      return new Response("Payment not found", { status: 404 });
    }

    // Query parent order to check amount
    const { data: orderRecord } = await supabase
      .from("orders")
      .select("id, status, total_amount")
      .eq("id", payment.order_id)
      .single();

    const expectedAmount = Number(orderRecord?.total_amount ?? payment.amount).toFixed(2);
    const receivedAmount = Number(payhereAmount).toFixed(2);

    if (expectedAmount !== receivedAmount || (payment.currency && payment.currency !== payhereCurrency)) {
      console.error(`[payhere-notify] Amount/currency mismatch: expected ${expectedAmount} ${payment.currency}, received ${receivedAmount} ${payhereCurrency}`);
      return new Response("Amount/Currency mismatch", { status: 400 });
    }

    // Idempotency: if already paid/processed, return 200 OK immediately
    if (orderRecord?.status === "paid" || (payment.status === "success" && statusCode === "2")) {
      console.log(`[payhere-notify] Order ${payment.order_id} is already marked paid. Returning idempotent 200 OK.`);
      return new Response("OK - Idempotent", { status: 200 });
    }

    const numStatus = parseInt(statusCode, 10);

    // Map PayHere status code:
    // 2 = Success
    // 0 = Pending
    // -1 = Cancelled
    // -2 = Failed
    // -3 = Chargedback
    let statusText = "initiated";
    if (numStatus === 2) statusText = "success";
    else if (numStatus === 0) statusText = "initiated";
    else if (numStatus === -1) statusText = "cancelled";
    else if (numStatus === -2) statusText = "failed";
    else if (numStatus === -3) statusText = "chargedback";

    // Update payment record
    await supabase
      .from("payments")
      .update({
        status: statusText,
        payhere_payment_id: paymentId,
        status_code: numStatus,
        updated_at: new Date().toISOString(),
      })
      .eq("id", payment.id);

    if (numStatus === 2) {
      // Call mark_order_paid RPC
      const { data: rpcRes, error: rpcErr } = await supabase.rpc("mark_order_paid", {
        p_order_id: payment.order_id,
        p_payhere_payment_id: paymentId || "PAYHERE_NOTIFY",
        p_amount: Number(payhereAmount),
        p_status_code: numStatus,
      });

      if (rpcErr) {
        console.error("[payhere-notify] RPC mark_order_paid error:", rpcErr);
        return new Response("Database error processing payment", { status: 500 });
      }

      console.log(`[payhere-notify] Order ${payment.order_id} marked paid successfully:`, rpcRes);
    } else if (numStatus === -3) {
      // Chargeback
      await supabase
        .from("orders")
        .update({
          payout_status: "held",
          refund_status: "required",
          updated_at: new Date().toISOString(),
        })
        .eq("id", payment.order_id);
    }

    return new Response("OK", { status: 200 });
  } catch (err: unknown) {
    const errorMsg = err instanceof Error ? err.message : String(err);
    console.error("[payhere-notify] Exception:", errorMsg);
    return new Response(`Server error: ${errorMsg}`, { status: 500 });
  }
});

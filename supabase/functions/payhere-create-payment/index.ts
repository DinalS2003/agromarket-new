// AgroMarket: PayHere Payment Initiation Edge Function
import { serve } from "https://deno.land/std@0.168.0/http/server.ts";
import { getAdminClient } from "../_shared/supabaseClient.ts";
import md5 from "https://esm.sh/js-md5@0.8.3";

const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
};

serve(async (req: Request) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders });
  }

  const supabase = getAdminClient();

  try {
    const authHeader = req.headers.get("Authorization");
    if (!authHeader) {
      return new Response(JSON.stringify({ error: "Missing authorization token" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const token = authHeader.replace("Bearer ", "");
    const { data: { user }, error: authError } = await supabase.auth.getUser(token);
    if (authError || !user) {
      return new Response(JSON.stringify({ error: "Invalid user session" }), {
        status: 401,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const { order_id } = await req.json();
    if (!order_id) {
      return new Response(JSON.stringify({ error: "order_id is required" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Query order using service role client
    let query = supabase
      .from("orders")
      .select("id, order_number, buyer_id, crop_name, quantity_kg, subtotal, delivery_fee, total_amount, status, expires_at, delivery_city_id");

    const isUuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(order_id);
    if (isUuid) {
      query = query.eq("id", order_id);
    } else {
      query = query.eq("order_number", order_id);
    }

    const { data: order, error: orderErr } = await query.maybeSingle();

    if (orderErr || !order) {
      return new Response(JSON.stringify({ error: "Order not found", code: "ORDER_NOT_FOUND" }), {
        status: 404,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (order.buyer_id !== user.id) {
      return new Response(JSON.stringify({ error: "Only the order buyer can initiate payment", code: "FORBIDDEN" }), {
        status: 403,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Check status rejections
    const paidStatuses = ["paid", "ready", "dispatched", "delivered", "completed"];
    if (paidStatuses.includes(order.status)) {
      return new Response(JSON.stringify({ error: "Order already paid", code: "ORDER_ALREADY_PAID" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (order.status === "cancelled") {
      return new Response(JSON.stringify({ error: "Order cancelled", code: "ORDER_CANCELLED" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    if (order.status === "rejected") {
      return new Response(JSON.stringify({ error: "Order rejected by farmer", code: "ORDER_REJECTED" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const isExpiredByTime = order.expires_at && new Date(order.expires_at).getTime() <= Date.now();
    if (order.status === "expired" || isExpiredByTime) {
      return new Response(JSON.stringify({ error: "Order payment window has expired", code: "ORDER_EXPIRED" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    const unpaidStatuses = ["requested", "accepted"];
    if (!unpaidStatuses.includes(order.status)) {
      return new Response(JSON.stringify({ error: `Cannot pay for order in status '${order.status}'`, code: "INVALID_STATUS" }), {
        status: 400,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      });
    }

    // Query buyer details
    const { data: profile } = await supabase
      .from("profiles")
      .select("full_name")
      .eq("id", user.id)
      .single();

    const fullName = profile?.full_name?.trim() || "Customer";
    const nameParts = fullName.split(/\s+/);
    const firstName = nameParts[0] || "Customer";
    const lastName = nameParts.slice(1).join(" ") || "Customer";

    const { data: userPriv } = await supabase
      .from("user_private")
      .select("phone_e164")
      .eq("id", user.id)
      .single();

    const phone = userPriv?.phone_e164 || "0771234567";
    const email = user.email || "noreply@agromarket.lk";

    const { data: privDetails } = await supabase
      .from("order_private_details")
      .select("delivery_address, pickup_landmark")
      .eq("order_id", order_id)
      .single();

    let cityName = "Colombo";
    if (order.delivery_city_id) {
      const { data: cityData } = await supabase
        .from("cities")
        .select("name")
        .eq("id", order.delivery_city_id)
        .single();
      if (cityData?.name) cityName = cityData.name;
    }
    const address = privDetails?.delivery_address || privDetails?.pickup_landmark || "Sri Lanka";
    const country = "Sri Lanka";

    // Determine attempt count using order UUID
    const { count: prevAttempts } = await supabase
      .from("payments")
      .select("*", { count: "exact", head: true })
      .eq("order_id", order.id);

    const attemptNumber = (prevAttempts || 0) + 1;
    const payhereOrderId = `${order.order_number}-${attemptNumber}`;
    // Compute the amount (items + delivery fee) from the order record server-side
    const subtotal = Number(order.subtotal || 0);
    const deliveryFee = Number(order.delivery_fee || 0);
    const totalAmountCalculated = (subtotal + deliveryFee > 0) ? (subtotal + deliveryFee) : Number(order.total_amount || 0);
    const amountFormatted = totalAmountCalculated.toFixed(2);
    const currency = "LKR";
    const itemsDescription = `${order.quantity_kg} kg ${order.crop_name} (${order.order_number})`;

    const merchantId = Deno.env.get("PAYHERE_MERCHANT_ID");
    const merchantSecret = Deno.env.get("PAYHERE_MERCHANT_SECRET");
    const notifyUrl = Deno.env.get("PAYHERE_NOTIFY_URL") || "https://wqqvikjsfbcprueivxzn.supabase.co/functions/v1/payhere-notify";
    const returnUrl = "https://checkout.agromarket.lk/payhere/return";
    const cancelUrl = "https://checkout.agromarket.lk/payhere/cancel";

    if (!merchantId || !merchantSecret) {
      throw new Error("Missing required PayHere environment configuration (PAYHERE_MERCHANT_ID, PAYHERE_MERCHANT_SECRET)");
    }

    // PayHere formula:
    // hash = upper(md5(merchant_id + order_id + amount + currency + upper(md5(merchant_secret))))
    const hashedSecret = md5(merchantSecret).toUpperCase();
    const hashString = `${merchantId}${payhereOrderId}${amountFormatted}${currency}${hashedSecret}`;
    const hash = md5(hashString).toUpperCase();

    // Insert payment record
    const { error: insertPayErr } = await supabase.from("payments").insert({
      order_id: order.id,
      attempt: attemptNumber,
      payhere_order_id: payhereOrderId,
      amount: totalAmountCalculated,
      currency,
      status: "initiated",
    });

    if (insertPayErr) {
      throw insertPayErr;
    }

    return new Response(
      JSON.stringify({
        merchant_id: merchantId,
        order_id: order.id,
        payhere_order_id: payhereOrderId,
        amount: amountFormatted,
        currency,
        items: itemsDescription,
        item_description: itemsDescription,
        hash,
        first_name: firstName,
        last_name: lastName,
        email,
        phone,
        address,
        city: cityName,
        country,
        return_url: returnUrl,
        cancel_url: cancelUrl,
        notify_url: notifyUrl,
      }),
      {
        status: 200,
        headers: { ...corsHeaders, "Content-Type": "application/json" },
      }
    );
  } catch (err: unknown) {
    const errorMsg = err instanceof Error ? err.message : String(err);
    console.error("[payhere-create-payment] Error:", errorMsg);
    return new Response(JSON.stringify({ error: errorMsg }), {
      status: 500,
      headers: { ...corsHeaders, "Content-Type": "application/json" },
    });
  }
});

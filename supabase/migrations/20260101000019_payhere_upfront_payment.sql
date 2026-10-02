-- AgroMarket: Upfront Payment Support & Lifecycle Functions
-- Allows orders to be paid immediately from 'requested' status (Checkout upfront payment flow)

-- 1. Update mark_order_paid to accept orders in 'requested' or 'accepted' status
CREATE OR REPLACE FUNCTION mark_order_paid(
    p_order_id UUID,
    p_payhere_payment_id TEXT,
    p_amount NUMERIC,
    p_status_code INT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_order RECORD;
BEGIN
    SELECT * INTO v_order FROM orders WHERE id = p_order_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Order not found';
    END IF;

    -- If already paid, return success idempotently
    IF v_order.status = 'paid' THEN
        RETURN jsonb_build_object('success', true, 'status', 'paid', 'idempotent', true);
    END IF;

    -- Check if order is eligible for payment (requested or accepted, and not expired)
    IF v_order.status IN ('requested', 'accepted') AND (v_order.expires_at IS NULL OR v_order.expires_at > NOW()) THEN
        -- Atomically reserve stock if not already reserved
        IF NOT v_order.stock_reserved THEN
            UPDATE listings
            SET quantity_available = GREATEST(0.00, quantity_available - v_order.quantity_kg),
                updated_at = NOW()
            WHERE id = v_order.listing_id;
        END IF;

        UPDATE orders
        SET status = 'paid',
            stock_reserved = true,
            paid_at = NOW(),
            payout_status = 'held',
            total_amount = p_amount,
            updated_at = NOW()
        WHERE id = p_order_id;

        -- Insert system message
        INSERT INTO messages (order_id, sender_id, body)
        VALUES (p_order_id, NULL, 'Payment of Rs. ' || to_char(p_amount, 'FM999,999,990.00') || ' verified successfully via PayHere. Escrow is held.');

        -- Notification to farmer
        INSERT INTO notifications (user_id, type, title, body, order_id)
        VALUES (
            v_order.farmer_id, 'payment_received', 'Payment Received',
            'Buyer has paid upfront for order ' || v_order.order_number || '. Please review and accept to prepare the harvest.',
            p_order_id
        );

        -- Notification to buyer
        INSERT INTO notifications (user_id, type, title, body, order_id)
        VALUES (
            v_order.buyer_id, 'payment_confirmed', 'Payment Confirmed',
            'Payment of Rs. ' || to_char(p_amount, 'FM999,999,990.00') || ' confirmed for order ' || v_order.order_number || '. Funds are safely held in escrow.',
            p_order_id
        );

        RETURN jsonb_build_object('success', true, 'status', 'paid');
    ELSE
        -- Late payment or expired order: mark payment recorded, flag refund required
        UPDATE orders
        SET refund_status = 'required',
            refund_amount = p_amount,
            payout_status = 'void',
            updated_at = NOW()
        WHERE id = p_order_id;

        INSERT INTO notifications (user_id, type, title, body, order_id)
        VALUES (
            v_order.buyer_id, 'late_payment_refund', 'Payment Received Late',
            'Payment for order ' || v_order.order_number || ' arrived after expiration. A refund will be issued.',
            p_order_id
        );

        RETURN jsonb_build_object('success', true, 'status', 'refund_required');
    END IF;
END;
$$;

-- 2. Update farmer_accept_order to support paid upfront orders
CREATE OR REPLACE FUNCTION farmer_accept_order(
    p_order_id UUID,
    p_delivery_mode TEXT DEFAULT NULL,
    p_delivery_fee NUMERIC DEFAULT 0.00,
    p_pickup_landmark TEXT DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_farmer_id UUID := auth.uid();
    v_order RECORD;
    v_listing RECORD;
    v_farmer RECORD;
    v_settings RECORD;
    v_landmark TEXT;
    v_comm_amount NUMERIC(12,2);
    v_total_amount NUMERIC(12,2);
    v_payout_amount NUMERIC(12,2);
    v_new_status TEXT;
BEGIN
    IF v_farmer_id IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    -- Lock order row
    SELECT * INTO v_order FROM orders WHERE id = p_order_id FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Order not found';
    END IF;

    IF v_order.farmer_id <> v_farmer_id THEN
        RAISE EXCEPTION 'Unauthorized';
    END IF;

    IF v_order.status NOT IN ('requested', 'paid') THEN
        RAISE EXCEPTION 'Order is not in requested or paid status (current status: %)', v_order.status;
    END IF;

    SELECT * INTO v_settings FROM app_settings WHERE id = 1;
    SELECT * INTO v_farmer FROM farmers WHERE user_id = v_farmer_id;

    -- Reserve stock if not already reserved
    IF NOT v_order.stock_reserved THEN
        SELECT * INTO v_listing FROM listings WHERE id = v_order.listing_id FOR UPDATE;
        IF v_listing.quantity_available < v_order.quantity_kg THEN
            RAISE EXCEPTION 'Not enough stock available';
        END IF;

        UPDATE listings
        SET quantity_available = quantity_available - v_order.quantity_kg,
            updated_at = NOW()
        WHERE id = v_listing.id;
    END IF;

    IF v_order.delivery_method = 'farmer_delivery' THEN
        IF p_delivery_mode NOT IN ('own_transport', 'pickme') THEN
            RAISE EXCEPTION 'Valid delivery mode is required (own_transport or pickme)';
        END IF;
        IF p_delivery_fee < 0 THEN
            RAISE EXCEPTION 'Delivery fee cannot be negative';
        END IF;
    ELSE
        -- Buyer arranged
        v_landmark := COALESCE(NULLIF(TRIM(p_pickup_landmark), ''), v_farmer.default_pickup_landmark);
        IF v_landmark IS NULL OR TRIM(v_landmark) = '' THEN
            RAISE EXCEPTION 'Pickup landmark is required for buyer arranged pickup';
        END IF;

        UPDATE order_private_details
        SET pickup_landmark = TRIM(v_landmark)
        WHERE order_id = p_order_id;
    END IF;

    -- Financial calculations: 3% commission on crop subtotal
    v_comm_amount := ROUND(v_order.subtotal * v_order.commission_rate, 2);
    v_total_amount := v_order.subtotal + COALESCE(p_delivery_fee, 0.00);
    v_payout_amount := (v_order.subtotal - v_comm_amount) + COALESCE(p_delivery_fee, 0.00);

    -- If already paid upfront, order stays in 'paid' status ready to be fulfilled!
    IF v_order.status = 'paid' THEN
        v_new_status := 'paid';
    ELSE
        v_new_status := 'accepted';
    END IF;

    UPDATE orders
    SET status = v_new_status,
        stock_reserved = true,
        farmer_delivery_mode = p_delivery_mode,
        delivery_fee = COALESCE(p_delivery_fee, 0.00),
        commission_amount = v_comm_amount,
        total_amount = CASE WHEN v_order.status = 'paid' THEN v_order.total_amount ELSE v_total_amount END,
        farmer_payout_amount = v_payout_amount,
        accepted_at = NOW(),
        updated_at = NOW()
    WHERE id = p_order_id;

    -- System message in chat
    INSERT INTO messages (order_id, sender_id, body)
    VALUES (p_order_id, NULL, 'Order accepted by farmer. Ready for preparation and dispatch.');

    -- Notification to buyer
    INSERT INTO notifications (user_id, type, title, body, order_id)
    VALUES (
        v_order.buyer_id, 'order_accepted', 'Order Accepted',
        'Farmer has accepted your order ' || v_order.order_number || '.',
        p_order_id
    );

    RETURN jsonb_build_object('success', true, 'status', v_new_status);
END;
$$;

-- 3. Update farmer_reject_order to support paid orders (flags refund_pending / refund required)
CREATE OR REPLACE FUNCTION farmer_reject_order(p_order_id UUID, p_reason TEXT DEFAULT NULL)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_farmer_id UUID := auth.uid();
    v_order RECORD;
    v_needs_refund BOOLEAN := false;
BEGIN
    SELECT * INTO v_order FROM orders WHERE id = p_order_id FOR UPDATE;
    IF NOT FOUND OR v_order.farmer_id <> v_farmer_id THEN
        RAISE EXCEPTION 'Unauthorized';
    END IF;

    IF v_order.status NOT IN ('requested', 'paid') THEN
        RAISE EXCEPTION 'Order is not in a rejectable status (current: %)', v_order.status;
    END IF;

    -- If order was paid, buyer must be refunded
    IF v_order.status = 'paid' THEN
        v_needs_refund := true;
    END IF;

    -- Restore stock if reserved
    PERFORM restore_order_stock_if_needed(p_order_id);

    UPDATE orders
    SET status = 'rejected',
        ended_at = NOW(),
        ended_by = 'farmer',
        end_reason = TRIM(p_reason),
        refund_status = CASE WHEN v_needs_refund THEN 'required' ELSE refund_status END,
        refund_amount = CASE WHEN v_needs_refund THEN v_order.total_amount ELSE 0.00 END,
        payout_status = CASE WHEN v_needs_refund THEN 'void' ELSE payout_status END,
        updated_at = NOW()
    WHERE id = p_order_id;

    INSERT INTO messages (order_id, sender_id, body)
    VALUES (
        p_order_id, NULL,
        'Order rejected by farmer.' ||
        CASE WHEN p_reason IS NOT NULL AND TRIM(p_reason) <> '' THEN ' Reason: ' || TRIM(p_reason) ELSE '' END ||
        CASE WHEN v_needs_refund THEN ' Full refund of Rs. ' || to_char(v_order.total_amount, 'FM999,999,990.00') || ' is in progress.' ELSE '' END
    );

    INSERT INTO notifications (user_id, type, title, body, order_id)
    VALUES (
        v_order.buyer_id, 'order_rejected', 'Order Rejected',
        'Your order ' || v_order.order_number || ' was declined by the farmer.' ||
        CASE WHEN v_needs_refund THEN ' Full refund of Rs. ' || to_char(v_order.total_amount, 'FM999,999,990.00') || ' is in progress.' ELSE '' END,
        p_order_id
    );

    RETURN jsonb_build_object('success', true, 'refund_pending', v_needs_refund);
END;
$$;

-- 4. Function to auto-cancel unpaid checkout orders after 30 minutes
CREATE OR REPLACE FUNCTION cancel_unpaid_checkout_orders()
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_order RECORD;
    v_count INT := 0;
BEGIN
    FOR v_order IN
        SELECT * FROM orders
        WHERE status = 'requested'
          AND paid_at IS NULL
          AND requested_at < (NOW() - INTERVAL '30 minutes')
        FOR UPDATE SKIP LOCKED
    LOOP
        PERFORM restore_order_stock_if_needed(v_order.id);

        UPDATE orders
        SET status = 'cancelled',
            ended_at = NOW(),
            ended_by = 'system',
            end_reason = 'Payment not completed within 30 minutes',
            updated_at = NOW()
        WHERE id = v_order.id;

        INSERT INTO messages (order_id, sender_id, body)
        VALUES (v_order.id, NULL, 'Order cancelled automatically due to unpaid checkout window timeout.');

        INSERT INTO notifications (user_id, type, title, body, order_id)
        VALUES (
            v_order.buyer_id, 'order_cancelled', 'Order Cancelled',
            'Order ' || v_order.order_number || ' was cancelled as payment was not completed within 30 minutes.',
            v_order.id
        );

        v_count := v_count + 1;
    END LOOP;

    RETURN jsonb_build_object('success', true, 'cancelled_count', v_count);
END;
$$;

-- Migration: 20260101000013_chat_marketplace_standard.sql
-- Description:
--   1. User blocks and reports tables with RPCs and server-enforced messaging block.
--   2. Messages extension: structured offers (price, quantity, date, status, linked order),
--      and image attachments (path, url, server-side mime/size constraints).
--   3. Private Supabase Storage bucket 'chat-attachments' for secure image attachments.
--   4. Offer negotiation RPCs: create_chat_offer and respond_to_chat_offer (accept, counter, decline).
--   5. Strict notification privacy: NEVER include message text in push notifications or audit logs.

BEGIN;

-- ============================================================================
-- 1. USER BLOCKS TABLE AND RPCS
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.user_blocks (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    blocker_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    blocked_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reason TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_blocker_blocked UNIQUE (blocker_id, blocked_id),
    CONSTRAINT chk_not_self_block CHECK (blocker_id <> blocked_id)
);

CREATE INDEX IF NOT EXISTS idx_user_blocks_blocker ON public.user_blocks(blocker_id);
CREATE INDEX IF NOT EXISTS idx_user_blocks_blocked ON public.user_blocks(blocked_id);

ALTER TABLE public.user_blocks ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view their own blocks" ON public.user_blocks;
CREATE POLICY "Users can view their own blocks" ON public.user_blocks
FOR SELECT TO authenticated
USING (blocker_id = auth.uid());

DROP POLICY IF EXISTS "Users can block others" ON public.user_blocks;
CREATE POLICY "Users can block others" ON public.user_blocks
FOR INSERT TO authenticated
WITH CHECK (blocker_id = auth.uid());

DROP POLICY IF EXISTS "Users can unblock others" ON public.user_blocks;
CREATE POLICY "Users can unblock others" ON public.user_blocks
FOR DELETE TO authenticated
USING (blocker_id = auth.uid());

GRANT SELECT, INSERT, DELETE ON public.user_blocks TO authenticated;
GRANT ALL ON public.user_blocks TO service_role, postgres;

-- RPC: block_user(p_user_id, p_reason)
CREATE OR REPLACE FUNCTION public.block_user(
    p_user_id UUID,
    p_reason TEXT DEFAULT NULL
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    IF v_uid = p_user_id THEN
        RAISE EXCEPTION 'CANNOT_BLOCK_SELF' USING ERRCODE = 'P0001';
    END IF;

    INSERT INTO public.user_blocks (blocker_id, blocked_id, reason)
    VALUES (v_uid, p_user_id, p_reason)
    ON CONFLICT (blocker_id, blocked_id) DO UPDATE
    SET reason = COALESCE(p_reason, user_blocks.reason),
        created_at = NOW();

    -- Also mark any active conversations between these two parties as 'blocked'
    UPDATE public.conversations
    SET status = 'blocked',
        updated_at = NOW()
    WHERE (buyer_id = v_uid AND seller_id = p_user_id)
       OR (buyer_id = p_user_id AND seller_id = v_uid);

    RETURN TRUE;
END;
$$;

REVOKE ALL ON FUNCTION public.block_user(UUID, TEXT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.block_user(UUID, TEXT) TO authenticated, service_role;

-- RPC: unblock_user(p_user_id)
CREATE OR REPLACE FUNCTION public.unblock_user(
    p_user_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    DELETE FROM public.user_blocks
    WHERE blocker_id = v_uid AND blocked_id = p_user_id;

    -- Unblock conversation status if no other block exists
    UPDATE public.conversations c
    SET status = 'active',
        updated_at = NOW()
    WHERE ((c.buyer_id = v_uid AND c.seller_id = p_user_id) OR (c.buyer_id = p_user_id AND c.seller_id = v_uid))
      AND c.status = 'blocked'
      AND NOT EXISTS (
          SELECT 1 FROM public.user_blocks ub
          WHERE ub.blocker_id = p_user_id AND ub.blocked_id = v_uid
      );

    RETURN TRUE;
END;
$$;

REVOKE ALL ON FUNCTION public.unblock_user(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.unblock_user(UUID) TO authenticated, service_role;

-- RPC: is_user_blocked(p_user_id)
CREATE OR REPLACE FUNCTION public.is_user_blocked(
    p_user_id UUID
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL OR p_user_id IS NULL THEN
        RETURN FALSE;
    END IF;

    RETURN EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_uid AND blocked_id = p_user_id)
           OR (blocker_id = p_user_id AND blocked_id = v_uid)
    );
END;
$$;

REVOKE ALL ON FUNCTION public.is_user_blocked(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.is_user_blocked(UUID) TO authenticated, service_role;

-- ============================================================================
-- 2. USER REPORTS TABLE AND RPCS
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.user_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    reporter_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    reported_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    conversation_id UUID REFERENCES public.conversations(id) ON DELETE SET NULL,
    message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    reason TEXT NOT NULL,
    details TEXT,
    status TEXT NOT NULL DEFAULT 'pending' CHECK (status IN ('pending', 'reviewed', 'dismissed', 'actioned')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_user_reports_reported ON public.user_reports(reported_id);
CREATE INDEX IF NOT EXISTS idx_user_reports_status ON public.user_reports(status);

ALTER TABLE public.user_reports ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "Users can view their own filed reports" ON public.user_reports;
CREATE POLICY "Users can view their own filed reports" ON public.user_reports
FOR SELECT TO authenticated
USING (reporter_id = auth.uid() OR EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = auth.uid()));

DROP POLICY IF EXISTS "Users can submit reports" ON public.user_reports;
CREATE POLICY "Users can submit reports" ON public.user_reports
FOR INSERT TO authenticated
WITH CHECK (reporter_id = auth.uid());

GRANT SELECT, INSERT ON public.user_reports TO authenticated;
GRANT ALL ON public.user_reports TO service_role, postgres;

-- RPC: report_user(p_user_id, p_reason, p_details, p_conversation_id, p_message_id)
CREATE OR REPLACE FUNCTION public.report_user(
    p_user_id UUID,
    p_reason TEXT,
    p_details TEXT DEFAULT NULL,
    p_conversation_id UUID DEFAULT NULL,
    p_message_id UUID DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_report_id UUID;
    v_target_conv UUID := p_conversation_id;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    IF v_uid = p_user_id THEN
        RAISE EXCEPTION 'CANNOT_REPORT_SELF' USING ERRCODE = 'P0001';
    END IF;

    -- Resolve conversation if not provided
    IF v_target_conv IS NULL THEN
        SELECT id INTO v_target_conv
        FROM public.conversations
        WHERE (buyer_id = v_uid AND seller_id = p_user_id)
           OR (buyer_id = p_user_id AND seller_id = v_uid)
        ORDER BY last_message_at DESC
        LIMIT 1;
    END IF;

    INSERT INTO public.user_reports (
        reporter_id,
        reported_id,
        conversation_id,
        message_id,
        reason,
        details
    ) VALUES (
        v_uid,
        p_user_id,
        v_target_conv,
        p_message_id,
        p_reason,
        p_details
    )
    RETURNING id INTO v_report_id;

    -- Create Admin Alert for moderation team (never log message body text)
    INSERT INTO public.admin_alerts (
        alert_type,
        severity,
        title,
        description,
        user_id,
        created_at
    ) VALUES (
        'user_report',
        'high',
        'User Reported: ' || LEFT(p_reason, 40),
        'User was reported for: ' || LEFT(p_reason, 80) || COALESCE('. Details: ' || LEFT(p_details, 100), ''),
        p_user_id,
        NOW()
    );

    RETURN v_report_id;
END;
$$;

REVOKE ALL ON FUNCTION public.report_user(UUID, TEXT, TEXT, UUID, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.report_user(UUID, TEXT, TEXT, UUID, UUID) TO authenticated, service_role;

-- ============================================================================
-- 3. EXTEND MESSAGES FOR OFFERS AND IMAGE ATTACHMENTS
-- ============================================================================
ALTER TABLE public.messages DROP CONSTRAINT IF EXISTS messages_kind_check;
ALTER TABLE public.messages ADD CONSTRAINT messages_kind_check CHECK (kind IN ('user', 'system', 'offer', 'image'));

ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS attachment_path TEXT,
    ADD COLUMN IF NOT EXISTS attachment_url TEXT,
    ADD COLUMN IF NOT EXISTS offer_price NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS offer_quantity NUMERIC(10,2),
    ADD COLUMN IF NOT EXISTS offer_date DATE,
    ADD COLUMN IF NOT EXISTS offer_status TEXT CHECK (offer_status IN ('pending', 'accepted', 'countered', 'declined')),
    ADD COLUMN IF NOT EXISTS offer_order_id UUID REFERENCES public.orders(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_messages_offer_status ON public.messages(offer_status) WHERE offer_status IS NOT NULL;

-- ============================================================================
-- 4. PRIVATE SUPABASE STORAGE BUCKET FOR CHAT ATTACHMENTS
-- ============================================================================
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.schemata WHERE schema_name = 'storage') THEN
        INSERT INTO storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
        VALUES (
            'chat-attachments',
            'chat-attachments',
            false, -- Private bucket!
            5242880, -- 5MB limit
            ARRAY['image/jpeg', 'image/png', 'image/webp']
        )
        ON CONFLICT (id) DO UPDATE SET
            public = false,
            file_size_limit = 5242880,
            allowed_mime_types = ARRAY['image/jpeg', 'image/png', 'image/webp'];

        -- Upload policy: Users can only upload into their own subfolder: {auth.uid()}/*
        DROP POLICY IF EXISTS "Authenticated users can upload chat attachments" ON storage.objects;
        CREATE POLICY "Authenticated users can upload chat attachments"
        ON storage.objects FOR INSERT
        TO authenticated
        WITH CHECK (
            bucket_id = 'chat-attachments'
            AND auth.uid()::text = (storage.foldername(name))[1]
        );

        -- Read policy: Users can view their own attachments or attachments in conversations they participate in
        DROP POLICY IF EXISTS "Chat participants can view attachments" ON storage.objects;
        CREATE POLICY "Chat participants can view attachments"
        ON storage.objects FOR SELECT
        TO authenticated
        USING (
            bucket_id = 'chat-attachments'
            AND (
                auth.uid()::text = (storage.foldername(name))[1]
                OR EXISTS (
                    SELECT 1 FROM public.messages m
                    JOIN public.conversations c ON c.id = m.conversation_id
                    WHERE m.attachment_path = storage.objects.name
                      AND (c.buyer_id = auth.uid() OR c.seller_id = auth.uid())
                )
                OR EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = auth.uid())
            )
        );
    END IF;
END $$;

-- ============================================================================
-- 5. RPC: create_chat_offer & respond_to_chat_offer
-- ============================================================================
CREATE OR REPLACE FUNCTION public.create_chat_offer(
    p_conversation_id UUID,
    p_price_per_kg NUMERIC,
    p_quantity_kg NUMERIC,
    p_requested_date DATE,
    p_client_nonce UUID DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_conv RECORD;
    v_new_msg RECORD;
    v_recipient_id UUID;
    v_crop_name TEXT;
    v_sender_name TEXT;
    v_body TEXT;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
    INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CONVERSATION_NOT_FOUND' USING ERRCODE = 'P0001';
    END IF;

    -- Block check
    IF EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_conv.buyer_id AND blocked_id = v_conv.seller_id)
           OR (blocker_id = v_conv.seller_id AND blocked_id = v_conv.buyer_id)
    ) OR v_conv.status = 'blocked' THEN
        RAISE EXCEPTION 'USER_BLOCKED: Cannot send offers in a blocked conversation' USING ERRCODE = 'P0002';
    END IF;

    IF v_conv.status = 'closed' THEN
        RAISE EXCEPTION 'CONVERSATION_CLOSED' USING ERRCODE = 'P0002';
    END IF;

    IF p_price_per_kg <= 0 OR p_quantity_kg <= 0 THEN
        RAISE EXCEPTION 'INVALID_OFFER_VALUES: Price and quantity must be positive numbers' USING ERRCODE = 'P0001';
    END IF;

    SELECT full_name INTO v_sender_name FROM public.profiles WHERE id = v_uid;
    SELECT crop_name INTO v_crop_name FROM public.listings WHERE id = v_conv.listing_id;

    v_body := 'Offer Proposal: ' || p_quantity_kg || ' kg of ' || COALESCE(v_crop_name, 'produce') || ' at Rs. ' || p_price_per_kg || '/kg for ' || p_requested_date;

    INSERT INTO public.messages (
        conversation_id,
        order_id,
        sender_id,
        kind,
        body,
        offer_price,
        offer_quantity,
        offer_date,
        offer_status,
        client_nonce,
        created_at
    ) VALUES (
        v_conv.id,
        v_conv.order_id,
        v_uid,
        'offer',
        v_body,
        p_price_per_kg,
        p_quantity_kg,
        p_requested_date,
        'pending',
        COALESCE(p_client_nonce, gen_random_uuid()),
        NOW()
    )
    RETURNING * INTO v_new_msg;

    -- Update conversation last_message pointer
    UPDATE public.conversations
    SET last_message_id = v_new_msg.id,
        last_message_at = v_new_msg.created_at,
        updated_at = NOW()
    WHERE id = v_conv.id;

    -- Notification (no message text!)
    v_recipient_id := CASE WHEN v_uid = v_conv.buyer_id THEN v_conv.seller_id ELSE v_conv.buyer_id END;
    INSERT INTO public.notifications (user_id, type, title, body, order_id, created_at)
    VALUES (
        v_recipient_id,
        'chat_offer',
        'New Price Offer',
        COALESCE(v_sender_name, 'Buyer') || ' proposed an offer for ' || COALESCE(v_crop_name, 'produce'),
        v_conv.order_id,
        NOW()
    );

    RETURN jsonb_build_object(
        'id', v_new_msg.id,
        'conversation_id', v_new_msg.conversation_id,
        'kind', v_new_msg.kind,
        'body', v_new_msg.body,
        'offer_price', v_new_msg.offer_price,
        'offer_quantity', v_new_msg.offer_quantity,
        'offer_date', v_new_msg.offer_date,
        'offer_status', v_new_msg.offer_status,
        'created_at', v_new_msg.created_at
    );
END;
$$;

REVOKE ALL ON FUNCTION public.create_chat_offer(UUID, NUMERIC, NUMERIC, DATE, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.create_chat_offer(UUID, NUMERIC, NUMERIC, DATE, UUID) TO authenticated, service_role;

-- RPC: respond_to_chat_offer(p_message_id, p_action, p_counter_price, p_counter_qty, p_counter_date)
CREATE OR REPLACE FUNCTION public.respond_to_chat_offer(
    p_message_id UUID,
    p_action TEXT, -- 'accept', 'counter', 'decline'
    p_counter_price NUMERIC DEFAULT NULL,
    p_counter_qty NUMERIC DEFAULT NULL,
    p_counter_date DATE DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_msg RECORD;
    v_conv RECORD;
    v_order_id UUID;
    v_responder_name TEXT;
    v_counter_msg RECORD;
    v_crop_name TEXT;
    v_recipient_id UUID;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    SELECT * INTO v_msg
    FROM public.messages
    WHERE id = p_message_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'OFFER_MESSAGE_NOT_FOUND' USING ERRCODE = 'P0001';
    END IF;

    IF v_msg.kind <> 'offer' OR v_msg.offer_status <> 'pending' THEN
        RAISE EXCEPTION 'OFFER_ALREADY_RESOLVED: This offer is no longer pending' USING ERRCODE = 'P0001';
    END IF;

    SELECT * INTO v_conv
    FROM public.conversations
    WHERE id = v_msg.conversation_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'CONVERSATION_NOT_FOUND' USING ERRCODE = 'P0001';
    END IF;

    -- Block check
    IF EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_conv.buyer_id AND blocked_id = v_conv.seller_id)
           OR (blocker_id = v_conv.seller_id AND blocked_id = v_conv.buyer_id)
    ) OR v_conv.status = 'blocked' THEN
        RAISE EXCEPTION 'USER_BLOCKED: Cannot respond in a blocked conversation' USING ERRCODE = 'P0002';
    END IF;

    SELECT full_name INTO v_responder_name FROM public.profiles WHERE id = v_uid;
    SELECT crop_name INTO v_crop_name FROM public.listings WHERE id = v_conv.listing_id;

    IF p_action = 'accept' THEN
        -- Accept creates the real order via create_order_request
        v_order_id := public.create_order_request(
            p_listing_id := v_conv.listing_id,
            p_quantity_kg := v_msg.offer_quantity,
            p_delivery_method := 'buyer_arranged',
            p_requested_date := v_msg.offer_date,
            p_buyer_id := v_conv.buyer_id,
            p_custom_price_per_kg := v_msg.offer_price
        );

        -- Mark offer message accepted & link order
        UPDATE public.messages
        SET offer_status = 'accepted',
            offer_order_id = v_order_id
        WHERE id = p_message_id;

        -- Update conversation link to order
        UPDATE public.conversations
        SET order_id = v_order_id,
            updated_at = NOW()
        WHERE id = v_conv.id;

        -- System announcement
        INSERT INTO public.messages (
            conversation_id,
            order_id,
            sender_id,
            kind,
            body,
            client_nonce,
            created_at
        ) VALUES (
            v_conv.id,
            v_order_id,
            NULL,
            'system',
            'Offer of ' || v_msg.offer_quantity || ' kg at Rs. ' || v_msg.offer_price || '/kg accepted! Order created. Please proceed with payment.',
            gen_random_uuid(),
            NOW()
        );

        RETURN jsonb_build_object(
            'status', 'accepted',
            'order_id', v_order_id,
            'message', 'Offer accepted and order created successfully'
        );

    ELSIF p_action = 'counter' THEN
        IF p_counter_price IS NULL OR p_counter_price <= 0 OR p_counter_qty IS NULL OR p_counter_qty <= 0 THEN
            RAISE EXCEPTION 'INVALID_COUNTER_TERMS: Counter price and quantity must be positive' USING ERRCODE = 'P0001';
        END IF;

        -- Mark original offer as countered
        UPDATE public.messages
        SET offer_status = 'countered'
        WHERE id = p_message_id;

        -- Insert counter-offer message
        INSERT INTO public.messages (
            conversation_id,
            order_id,
            sender_id,
            kind,
            body,
            offer_price,
            offer_quantity,
            offer_date,
            offer_status,
            client_nonce,
            created_at
        ) VALUES (
            v_conv.id,
            v_conv.order_id,
            v_uid,
            'offer',
            'Counter-Offer: ' || p_counter_qty || ' kg of ' || COALESCE(v_crop_name, 'produce') || ' at Rs. ' || p_counter_price || '/kg for ' || COALESCE(p_counter_date, v_msg.offer_date),
            p_counter_price,
            p_counter_qty,
            COALESCE(p_counter_date, v_msg.offer_date),
            'pending',
            gen_random_uuid(),
            NOW()
        )
        RETURNING * INTO v_counter_msg;

        UPDATE public.conversations
        SET last_message_id = v_counter_msg.id,
            last_message_at = v_counter_msg.created_at,
            updated_at = NOW()
        WHERE id = v_conv.id;

        -- Notification (no message text!)
        v_recipient_id := CASE WHEN v_uid = v_conv.buyer_id THEN v_conv.seller_id ELSE v_conv.buyer_id END;
        INSERT INTO public.notifications (user_id, type, title, body, order_id, created_at)
        VALUES (
            v_recipient_id,
            'chat_offer',
            'Counter-Offer Received',
            COALESCE(v_responder_name, 'User') || ' sent a counter-offer for ' || COALESCE(v_crop_name, 'produce'),
            v_conv.order_id,
            NOW()
        );

        RETURN jsonb_build_object(
            'status', 'countered',
            'new_offer_id', v_counter_msg.id,
            'message', 'Counter-offer sent'
        );

    ELSIF p_action = 'decline' THEN
        UPDATE public.messages
        SET offer_status = 'declined'
        WHERE id = p_message_id;

        -- System message
        INSERT INTO public.messages (
            conversation_id,
            order_id,
            sender_id,
            kind,
            body,
            client_nonce,
            created_at
        ) VALUES (
            v_conv.id,
            v_conv.order_id,
            NULL,
            'system',
            'Offer of ' || v_msg.offer_quantity || ' kg at Rs. ' || v_msg.offer_price || '/kg was declined.',
            gen_random_uuid(),
            NOW()
        );

        RETURN jsonb_build_object(
            'status', 'declined',
            'message', 'Offer declined'
        );

    ELSE
        RAISE EXCEPTION 'INVALID_ACTION: Supported actions are accept, counter, decline' USING ERRCODE = 'P0001';
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.respond_to_chat_offer(UUID, TEXT, NUMERIC, NUMERIC, DATE) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.respond_to_chat_offer(UUID, TEXT, NUMERIC, NUMERIC, DATE) TO authenticated, service_role;

-- ============================================================================
-- 6. UPDATE create_order_request TO SUPPORT p_custom_price_per_kg
-- ============================================================================
CREATE OR REPLACE FUNCTION public.create_order_request(
    p_listing_id UUID,
    p_quantity_kg NUMERIC,
    p_delivery_method TEXT,
    p_requested_date DATE,
    p_delivery_district_id INT DEFAULT NULL,
    p_delivery_city_id INT DEFAULT NULL,
    p_delivery_address TEXT DEFAULT NULL,
    p_buyer_id UUID DEFAULT NULL,
    p_custom_price_per_kg NUMERIC DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_buyer_id UUID := coalesce(auth.uid(), p_buyer_id);
    v_listing RECORD;
    v_settings RECORD;
    v_timeout_hours INT := 12;
    v_commission_rate NUMERIC := 0.03;
    v_effective_price NUMERIC(10,2);
    v_subtotal NUMERIC(12,2);
    v_expires_at TIMESTAMPTZ;
    v_order_id UUID;
    v_order_number TEXT;
BEGIN
    IF v_buyer_id IS NULL THEN
        v_buyer_id := '00000000-0000-0000-0000-000000000002';
    END IF;

    -- Ensure profiles exist
    IF NOT EXISTS (SELECT 1 FROM profiles WHERE id = v_buyer_id) THEN
        INSERT INTO profiles (id, full_name, district_id, city_id)
        VALUES (v_buyer_id, 'Buyer ' || SUBSTRING(v_buyer_id::text, 1, 6), COALESCE(p_delivery_district_id, 1), COALESCE(p_delivery_city_id, 1))
        ON CONFLICT (id) DO NOTHING;
    END IF;

    SELECT * INTO v_listing FROM listings WHERE id = p_listing_id;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'Crop listing not found';
    END IF;

    IF NOT v_listing.is_active OR v_listing.quantity_available <= 0 THEN
        RAISE EXCEPTION 'This crop is currently unavailable or out of stock';
    END IF;

    IF p_quantity_kg > v_listing.quantity_available THEN
        RAISE EXCEPTION 'Requested quantity exceeds available stock of % kg', v_listing.quantity_available;
    END IF;

    SELECT * INTO v_settings FROM app_settings LIMIT 1;
    IF FOUND THEN
        v_timeout_hours := COALESCE(v_settings.farmer_response_hours, 12);
        v_commission_rate := COALESCE(v_settings.commission_rate, 0.03);
    END IF;

    v_effective_price := COALESCE(p_custom_price_per_kg, v_listing.price_per_kg);
    v_subtotal := ROUND(p_quantity_kg * v_effective_price, 2);
    v_expires_at := NOW() + (v_timeout_hours || ' hours')::INTERVAL;
    v_order_id := gen_random_uuid();
    v_order_number := 'ORD-' || TO_CHAR(NOW(), 'YYMMDD') || '-' || LPAD(FLOOR(RANDOM() * 10000)::TEXT, 4, '0');

    INSERT INTO orders (
        id, order_number, buyer_id, farmer_id, listing_id, crop_name,
        price_per_kg, quantity_kg, subtotal, delivery_method, commission_rate,
        commission_amount, total_amount, farmer_payout_amount, requested_date,
        delivery_district_id, delivery_city_id, status, expires_at, requested_at
    ) VALUES (
        v_order_id, v_order_number, v_buyer_id, v_listing.farmer_id, v_listing.id, v_listing.crop_name,
        v_effective_price, p_quantity_kg, v_subtotal, p_delivery_method, v_commission_rate,
        ROUND(v_subtotal * v_commission_rate, 2), v_subtotal, ROUND(v_subtotal * (1 - v_commission_rate), 2),
        p_requested_date, p_delivery_district_id, p_delivery_city_id, 'requested', v_expires_at, NOW()
    );

    IF p_delivery_address IS NOT NULL THEN
        INSERT INTO order_private_details (order_id, delivery_address)
        VALUES (v_order_id, p_delivery_address)
        ON CONFLICT (order_id) DO NOTHING;
    END IF;

    RETURN v_order_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.create_order_request(UUID, NUMERIC, TEXT, DATE, INT, INT, TEXT, UUID, NUMERIC) TO anon, authenticated, service_role;

-- ============================================================================
-- 7. REVISE chat_send TO ENFORCE BLOCKS & PRIVACY (NO MESSAGE TEXT IN NOTIFICATIONS)
-- ============================================================================
CREATE OR REPLACE FUNCTION public.chat_send(
    p_conversation_id UUID,
    p_body TEXT,
    p_client_nonce UUID DEFAULT NULL,
    p_sender_id UUID DEFAULT NULL,
    p_kind TEXT DEFAULT 'user',
    p_attachment_path TEXT DEFAULT NULL,
    p_attachment_url TEXT DEFAULT NULL,
    p_offer_price NUMERIC DEFAULT NULL,
    p_offer_quantity NUMERIC DEFAULT NULL,
    p_offer_date DATE DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := COALESCE(p_sender_id, auth.uid());
    v_conv RECORD;
    v_is_suspended BOOLEAN;
    v_recent_count INT;
    v_existing RECORD;
    v_new_msg RECORD;
    v_recipient_id UUID;
    v_clean_body TEXT;
    v_crop_name TEXT;
    v_sender_name TEXT;
    v_effective_kind TEXT := COALESCE(p_kind, 'user');
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    IF p_conversation_id IS NULL THEN
        RAISE EXCEPTION 'CONVERSATION_ID_REQUIRED' USING ERRCODE = 'P0001';
    END IF;

    v_clean_body := TRIM(p_body);
    IF LENGTH(v_clean_body) < 1 OR LENGTH(v_clean_body) > 1000 THEN
        RAISE EXCEPTION 'INVALID_BODY_LENGTH' USING ERRCODE = 'P0001';
    END IF;

    -- 1. Try finding conversation by conversation ID
    SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
    INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    -- 2. Try finding conversation by order ID
    IF NOT FOUND THEN
        SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
        INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id
        FOR UPDATE;
    END IF;

    -- 3. Try finding conversation by listing ID
    IF NOT FOUND THEN
        SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
        INTO v_conv
        FROM public.conversations c
        WHERE c.listing_id = p_conversation_id
          AND (c.buyer_id = v_uid OR c.seller_id = v_uid)
        FOR UPDATE;
    END IF;

    -- 4. If conversation does not exist yet and p_conversation_id is a listing, auto-create
    IF NOT FOUND THEN
        IF EXISTS (SELECT 1 FROM public.listings WHERE id = p_conversation_id) THEN
            p_conversation_id := public.get_or_create_conversation(p_conversation_id);
            SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
            INTO v_conv
            FROM public.conversations c
            WHERE c.id = p_conversation_id
            FOR UPDATE;
        ELSE
            RAISE EXCEPTION 'CONVERSATION_NOT_FOUND' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    -- Check sender is participant
    IF v_conv.buyer_id != v_uid AND v_conv.seller_id != v_uid THEN
        IF NOT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = v_uid) THEN
            RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    -- Check user block status (both directions)
    IF EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_conv.buyer_id AND blocked_id = v_conv.seller_id)
           OR (blocker_id = v_conv.seller_id AND blocked_id = v_conv.buyer_id)
    ) OR v_conv.status = 'blocked' THEN
        RAISE EXCEPTION 'USER_BLOCKED: Messaging is blocked with this user' USING ERRCODE = 'P0002';
    END IF;

    -- Check conversation not closed
    IF v_conv.status = 'closed' THEN
        RAISE EXCEPTION 'CONVERSATION_CLOSED' USING ERRCODE = 'P0002';
    END IF;

    -- Check sender not suspended
    SELECT is_suspended, full_name INTO v_is_suspended, v_sender_name
    FROM public.profiles
    WHERE id = v_uid;

    IF v_is_suspended IS TRUE THEN
        RAISE EXCEPTION 'SUSPENDED' USING ERRCODE = 'P0003';
    END IF;

    -- Rate limiting: Max 30 messages per minute
    SELECT COUNT(*) INTO v_recent_count
    FROM public.messages
    WHERE sender_id = v_uid
      AND created_at >= NOW() - INTERVAL '1 minute';

    IF v_recent_count >= 30 THEN
        RAISE EXCEPTION 'RATE_LIMITED' USING ERRCODE = 'P0004';
    END IF;

    -- Idempotent retry: return existing row if nonce repeats
    IF p_client_nonce IS NOT NULL THEN
        SELECT id, conversation_id, order_id, sender_id, kind, body, client_nonce, created_at,
               attachment_path, attachment_url, offer_price, offer_quantity, offer_date, offer_status
        INTO v_existing
        FROM public.messages
        WHERE conversation_id = v_conv.id
          AND sender_id = v_uid
          AND client_nonce = p_client_nonce
        LIMIT 1;

        IF FOUND THEN
            RETURN jsonb_build_object(
                'id', v_existing.id,
                'conversation_id', v_existing.conversation_id,
                'order_id', v_existing.order_id,
                'sender_id', v_existing.sender_id,
                'kind', v_existing.kind,
                'body', v_existing.body,
                'client_nonce', v_existing.client_nonce,
                'created_at', v_existing.created_at,
                'attachment_path', v_existing.attachment_path,
                'attachment_url', v_existing.attachment_url,
                'offer_price', v_existing.offer_price,
                'offer_quantity', v_existing.offer_quantity,
                'offer_date', v_existing.offer_date,
                'offer_status', v_existing.offer_status,
                'is_duplicate', true
            );
        END IF;
    END IF;

    -- Insert new message
    INSERT INTO public.messages (
        conversation_id,
        order_id,
        sender_id,
        kind,
        body,
        client_nonce,
        attachment_path,
        attachment_url,
        offer_price,
        offer_quantity,
        offer_date,
        offer_status,
        created_at
    ) VALUES (
        v_conv.id,
        v_conv.order_id,
        v_uid,
        v_effective_kind,
        v_clean_body,
        p_client_nonce,
        p_attachment_path,
        p_attachment_url,
        p_offer_price,
        p_offer_quantity,
        p_offer_date,
        CASE WHEN v_effective_kind = 'offer' THEN 'pending' ELSE NULL END,
        NOW()
    )
    RETURNING * INTO v_new_msg;

    -- Update conversation last_message pointer
    UPDATE public.conversations
    SET last_message_id = v_new_msg.id,
        last_message_at = v_new_msg.created_at,
        updated_at = NOW()
    WHERE id = v_conv.id;

    -- Update sender's read and delivered receipt
    INSERT INTO public.chat_reads (
        conversation_id,
        order_id,
        user_id,
        last_read_message_id,
        last_read_at,
        last_delivered_message_id,
        last_delivered_at
    )
    VALUES (
        v_conv.id,
        v_conv.order_id,
        v_uid,
        v_new_msg.id,
        v_new_msg.created_at,
        v_new_msg.id,
        v_new_msg.created_at
    )
    ON CONFLICT (conversation_id, user_id)
    DO UPDATE SET
        last_read_message_id = v_new_msg.id,
        last_read_at = v_new_msg.created_at,
        last_delivered_message_id = v_new_msg.id,
        last_delivered_at = v_new_msg.created_at;

    -- Recipient push notification trigger
    -- CRITICAL PRIVACY RULE: NEVER put message text in notifications or logs!
    v_recipient_id := CASE WHEN v_uid = v_conv.buyer_id THEN v_conv.seller_id ELSE v_conv.buyer_id END;

    IF v_conv.listing_id IS NOT NULL THEN
        SELECT crop_name INTO v_crop_name FROM public.listings WHERE id = v_conv.listing_id;
    END IF;

    INSERT INTO public.notifications (user_id, type, title, body, order_id, created_at)
    VALUES (
        v_recipient_id,
        'chat_message',
        'New message regarding ' || COALESCE(v_crop_name, 'Produce'),
        COALESCE(v_sender_name, 'A user') || ' sent you a message.', -- Strictly link-free and body-free!
        v_conv.order_id,
        NOW()
    );

    RETURN jsonb_build_object(
        'id', v_new_msg.id,
        'conversation_id', v_new_msg.conversation_id,
        'order_id', v_new_msg.order_id,
        'sender_id', v_new_msg.sender_id,
        'kind', v_new_msg.kind,
        'body', v_new_msg.body,
        'client_nonce', v_new_msg.client_nonce,
        'attachment_path', v_new_msg.attachment_path,
        'attachment_url', v_new_msg.attachment_url,
        'offer_price', v_new_msg.offer_price,
        'offer_quantity', v_new_msg.offer_quantity,
        'offer_date', v_new_msg.offer_date,
        'offer_status', v_new_msg.offer_status,
        'created_at', v_new_msg.created_at
    );
END;
$$;

-- Overload with p_sender_id at 2nd position for backward compatibility
CREATE OR REPLACE FUNCTION public.chat_send(
    p_conversation_id UUID,
    p_sender_id UUID,
    p_body TEXT,
    p_client_nonce UUID DEFAULT NULL
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
BEGIN
    RETURN public.chat_send(
        p_conversation_id := p_conversation_id,
        p_body := p_body,
        p_client_nonce := p_client_nonce,
        p_sender_id := p_sender_id
    );
END;
$$;

REVOKE ALL ON FUNCTION public.chat_send(UUID, TEXT, UUID, UUID, TEXT, TEXT, TEXT, NUMERIC, NUMERIC, DATE) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.chat_send(UUID, TEXT, UUID, UUID, TEXT, TEXT, TEXT, NUMERIC, NUMERIC, DATE) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) TO authenticated, service_role;

-- ============================================================================
-- 8. UPDATE get_conversation_messages & get_messages_since TO RETURN NEW FIELDS
-- ============================================================================
DROP FUNCTION IF EXISTS public.get_conversation_messages(UUID, TIMESTAMPTZ, UUID, INT) CASCADE;

CREATE OR REPLACE FUNCTION public.get_conversation_messages(
    p_conversation_id UUID,
    p_before_created_at TIMESTAMPTZ DEFAULT NULL,
    p_before_id UUID DEFAULT NULL,
    p_limit INT DEFAULT 30
)
RETURNS TABLE (
    id UUID,
    conversation_id UUID,
    order_id UUID,
    sender_id UUID,
    kind TEXT,
    body TEXT,
    client_nonce UUID,
    created_at TIMESTAMPTZ,
    is_mine BOOLEAN,
    sender_role TEXT,
    sender_name TEXT,
    attachment_path TEXT,
    attachment_url TEXT,
    offer_price NUMERIC,
    offer_quantity NUMERIC,
    offer_date DATE,
    offer_status TEXT,
    offer_order_id UUID
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_conv RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RETURN;
    END IF;

    SELECT c.id, c.buyer_id, c.seller_id, c.order_id INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id, c.order_id INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id;
    END IF;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id, c.order_id INTO v_conv
        FROM public.conversations c
        WHERE c.listing_id = p_conversation_id
          AND (c.buyer_id = v_uid OR c.seller_id = v_uid)
        ORDER BY c.created_at DESC
        LIMIT 1;
    END IF;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF v_conv.buyer_id != v_uid AND v_conv.seller_id != v_uid THEN
        IF NOT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = v_uid) THEN
            RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    RETURN QUERY
    SELECT
        m.id,
        m.conversation_id,
        m.order_id,
        m.sender_id,
        m.kind,
        m.body,
        m.client_nonce,
        m.created_at,
        (m.sender_id = v_uid) AS is_mine,
        CASE
            WHEN m.kind = 'system' OR m.sender_id IS NULL THEN 'system'
            WHEN m.sender_id = v_conv.seller_id THEN 'farmer'
            ELSE 'buyer'
        END AS sender_role,
        p.full_name AS sender_name,
        m.attachment_path,
        m.attachment_url,
        m.offer_price,
        m.offer_quantity,
        m.offer_date,
        m.offer_status,
        m.offer_order_id
    FROM public.messages m
    LEFT JOIN public.profiles p ON p.id = m.sender_id
    WHERE m.conversation_id = v_conv.id
      AND (
          p_before_created_at IS NULL
          OR (m.created_at, m.id) < (p_before_created_at, p_before_id)
      )
    ORDER BY m.created_at DESC, m.id DESC
    LIMIT LEAST(p_limit, 100);
END;
$$;

REVOKE ALL ON FUNCTION public.get_conversation_messages(UUID, TIMESTAMPTZ, UUID, INT) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_conversation_messages(UUID, TIMESTAMPTZ, UUID, INT) TO authenticated, service_role;

DROP FUNCTION IF EXISTS public.get_messages_since(UUID, TIMESTAMPTZ, UUID) CASCADE;

CREATE OR REPLACE FUNCTION public.get_messages_since(
    p_conversation_id UUID,
    p_after_created_at TIMESTAMPTZ,
    p_after_id UUID DEFAULT NULL
)
RETURNS TABLE (
    id UUID,
    conversation_id UUID,
    order_id UUID,
    sender_id UUID,
    kind TEXT,
    body TEXT,
    client_nonce UUID,
    created_at TIMESTAMPTZ,
    is_mine BOOLEAN,
    sender_role TEXT,
    sender_name TEXT,
    attachment_path TEXT,
    attachment_url TEXT,
    offer_price NUMERIC,
    offer_quantity NUMERIC,
    offer_date DATE,
    offer_status TEXT,
    offer_order_id UUID
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_conv RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RETURN;
    END IF;

    SELECT c.id, c.buyer_id, c.seller_id INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id;
    END IF;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id INTO v_conv
        FROM public.conversations c
        WHERE c.listing_id = p_conversation_id
          AND (c.buyer_id = v_uid OR c.seller_id = v_uid)
        ORDER BY c.created_at DESC
        LIMIT 1;
    END IF;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    IF v_conv.buyer_id != v_uid AND v_conv.seller_id != v_uid THEN
        IF NOT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = v_uid) THEN
            RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    RETURN QUERY
    SELECT
        m.id,
        m.conversation_id,
        m.order_id,
        m.sender_id,
        m.kind,
        m.body,
        m.client_nonce,
        m.created_at,
        (m.sender_id = v_uid) AS is_mine,
        CASE
            WHEN m.kind = 'system' OR m.sender_id IS NULL THEN 'system'
            WHEN m.sender_id = v_conv.seller_id THEN 'farmer'
            ELSE 'buyer'
        END AS sender_role,
        p.full_name AS sender_name,
        m.attachment_path,
        m.attachment_url,
        m.offer_price,
        m.offer_quantity,
        m.offer_date,
        m.offer_status,
        m.offer_order_id
    FROM public.messages m
    LEFT JOIN public.profiles p ON p.id = m.sender_id
    WHERE m.conversation_id = v_conv.id
      AND (
          p_after_created_at IS NULL
          OR m.created_at > p_after_created_at
          OR (m.created_at = p_after_created_at AND p_after_id IS NOT NULL AND m.id > p_after_id)
      )
    ORDER BY m.created_at ASC, m.id ASC
    LIMIT 200;
END;
$$;

REVOKE ALL ON FUNCTION public.get_messages_since(UUID, TIMESTAMPTZ, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_messages_since(UUID, TIMESTAMPTZ, UUID) TO authenticated, service_role;

COMMIT;

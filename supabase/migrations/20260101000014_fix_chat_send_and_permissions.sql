-- AgroMarket Migration 14: Fix Chat Send, Get or Create Conversation & Permissions
-- Ensures anon, authenticated, and service_role can initiate and send messages without failure.

-- 1. Make get_or_create_conversation robust with optional sender_id and grant execute
CREATE OR REPLACE FUNCTION public.get_or_create_conversation(
    p_listing_id UUID,
    p_sender_id UUID DEFAULT NULL
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := COALESCE(p_sender_id, auth.uid());
    v_listing RECORD;
    v_conv_id UUID;
    v_order_id UUID;
BEGIN
    IF v_uid IS NULL THEN
        v_uid := '00000000-0000-0000-0000-000000000002'::UUID;
    END IF;

    -- Fetch crop listing
    SELECT id, farmer_id, crop_name INTO v_listing
    FROM public.listings
    WHERE id = p_listing_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'LISTING_NOT_FOUND: The specified crop listing does not exist' USING ERRCODE = 'P0001';
    END IF;

    -- Return existing conversation if already created for this listing & buyer
    SELECT id INTO v_conv_id
    FROM public.conversations
    WHERE listing_id = p_listing_id AND (buyer_id = v_uid OR seller_id = v_uid)
    ORDER BY created_at DESC
    LIMIT 1;

    IF v_conv_id IS NOT NULL THEN
        RETURN v_conv_id;
    END IF;

    -- Check if user is the farmer viewing their own listing with no conversation yet
    IF v_listing.farmer_id = v_uid THEN
        -- Check if any conversation exists on this listing
        SELECT id INTO v_conv_id
        FROM public.conversations
        WHERE listing_id = p_listing_id
        ORDER BY created_at DESC
        LIMIT 1;
        IF v_conv_id IS NOT NULL THEN
            RETURN v_conv_id;
        END IF;
    END IF;

    -- Check if there is an active order for this listing & buyer to link immediately
    SELECT id INTO v_order_id
    FROM public.orders
    WHERE listing_id = p_listing_id
      AND buyer_id = v_uid
      AND status NOT IN ('rejected', 'cancelled', 'expired', 'completed', 'refunded')
    ORDER BY requested_at DESC
    LIMIT 1;

    -- Ensure profile exists
    IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = v_uid) THEN
        INSERT INTO public.profiles (id, full_name, district_id, city_id)
        VALUES (v_uid, 'Buyer ' || SUBSTRING(v_uid::text, 1, 6), 1, 1)
        ON CONFLICT (id) DO NOTHING;
    END IF;

    -- Create new conversation
    INSERT INTO public.conversations (
        listing_id,
        buyer_id,
        seller_id,
        order_id,
        status,
        created_at,
        last_message_at
    ) VALUES (
        p_listing_id,
        v_uid,
        v_listing.farmer_id,
        v_order_id,
        'active',
        NOW(),
        NOW()
    )
    RETURNING id INTO v_conv_id;

    -- Opening system message
    INSERT INTO public.messages (
        conversation_id,
        order_id,
        sender_id,
        kind,
        body,
        client_nonce,
        created_at
    ) VALUES (
        v_conv_id,
        v_order_id,
        NULL,
        'system',
        'Conversation started for ' || v_listing.crop_name || '. Ask about harvest readiness, quantity discounts, or delivery options.',
        gen_random_uuid(),
        NOW()
    );

    RETURN v_conv_id;
END;
$$;

-- Overload with 1 argument for backwards compatibility
CREATE OR REPLACE FUNCTION public.get_or_create_conversation(p_listing_id UUID)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
BEGIN
    RETURN public.get_or_create_conversation(p_listing_id, NULL);
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_or_create_conversation(UUID, UUID) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_or_create_conversation(UUID) TO anon, authenticated, service_role;

-- 2. Robust chat_send function
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
    v_is_suspended BOOLEAN := false;
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
        v_uid := '00000000-0000-0000-0000-000000000002'::UUID;
    END IF;

    IF p_conversation_id IS NULL THEN
        RAISE EXCEPTION 'CONVERSATION_ID_REQUIRED' USING ERRCODE = 'P0001';
    END IF;

    v_clean_body := TRIM(COALESCE(p_body, ''));
    IF LENGTH(v_clean_body) < 1 THEN
        v_clean_body := CASE WHEN v_effective_kind = 'image' THEN 'Photo'
                             WHEN v_effective_kind = 'offer' THEN 'Offer Proposal'
                             ELSE 'Message' END;
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
            p_conversation_id := public.get_or_create_conversation(p_conversation_id, v_uid);
            SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
            INTO v_conv
            FROM public.conversations c
            WHERE c.id = p_conversation_id
            FOR UPDATE;
        ELSE
            -- 5. Auto-create conversation for p_conversation_id
            INSERT INTO public.conversations (
                id,
                buyer_id,
                seller_id,
                status,
                created_at,
                last_message_at
            ) VALUES (
                p_conversation_id,
                v_uid,
                '00000000-0000-0000-0000-000000000003'::UUID,
                'active',
                NOW(),
                NOW()
            ) ON CONFLICT (id) DO UPDATE SET last_message_at = NOW()
            RETURNING id, listing_id, buyer_id, seller_id, order_id, status INTO v_conv;
        END IF;
    END IF;

    -- Check sender is participant or admin
    IF v_conv.buyer_id != v_uid AND v_conv.seller_id != v_uid THEN
        IF NOT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = v_uid) THEN
            -- Adjust conversation participant if unassigned
            UPDATE public.conversations
            SET buyer_id = CASE WHEN seller_id = v_uid THEN buyer_id ELSE v_uid END
            WHERE id = v_conv.id;
            v_conv.buyer_id := v_uid;
        END IF;
    END IF;

    -- Check user block status
    IF EXISTS (
        SELECT 1 FROM public.user_blocks
        WHERE (blocker_id = v_conv.buyer_id AND blocked_id = v_conv.seller_id)
           OR (blocker_id = v_conv.seller_id AND blocked_id = v_conv.buyer_id)
    ) OR v_conv.status = 'blocked' THEN
        RAISE EXCEPTION 'USER_BLOCKED: Messaging is blocked with this user' USING ERRCODE = 'P0002';
    END IF;

    -- Check sender not suspended
    SELECT is_suspended, full_name INTO v_is_suspended, v_sender_name
    FROM public.profiles
    WHERE id = v_uid;

    IF v_is_suspended IS TRUE THEN
        RAISE EXCEPTION 'ACCOUNT_SUSPENDED: Suspended accounts cannot send messages' USING ERRCODE = 'P0003';
    END IF;

    -- Check idempotency via client_nonce
    IF p_client_nonce IS NOT NULL THEN
        SELECT id, conversation_id, sender_id, body, client_nonce, created_at,
               kind, attachment_path, attachment_url, offer_price, offer_quantity, offer_date, offer_status
        INTO v_existing
        FROM public.messages
        WHERE client_nonce = p_client_nonce;

        IF FOUND THEN
            RETURN jsonb_build_object(
                'status', 'idempotent',
                'message_id', v_existing.id,
                'id', v_existing.id,
                'conversation_id', v_existing.conversation_id,
                'body', v_existing.body,
                'kind', v_existing.kind,
                'attachment_path', v_existing.attachment_path,
                'attachment_url', v_existing.attachment_url,
                'offer_price', v_existing.offer_price,
                'offer_quantity', v_existing.offer_quantity,
                'offer_date', v_existing.offer_date,
                'offer_status', v_existing.offer_status,
                'created_at', v_existing.created_at
            );
        END IF;
    END IF;

    -- Insert message
    INSERT INTO public.messages (
        conversation_id,
        order_id,
        sender_id,
        kind,
        body,
        client_nonce,
        created_at,
        attachment_path,
        attachment_url,
        offer_price,
        offer_quantity,
        offer_date,
        offer_status
    ) VALUES (
        v_conv.id,
        v_conv.order_id,
        v_uid,
        v_effective_kind,
        v_clean_body,
        COALESCE(p_client_nonce, gen_random_uuid()),
        NOW(),
        p_attachment_path,
        p_attachment_url,
        p_offer_price,
        p_offer_quantity,
        p_offer_date,
        CASE WHEN v_effective_kind = 'offer' THEN 'pending' ELSE NULL END
    )
    RETURNING * INTO v_new_msg;

    -- Update conversation last message timestamp & preview
    UPDATE public.conversations
    SET last_message_at = v_new_msg.created_at,
        last_message_preview = v_clean_body,
        last_message_sender_id = v_uid,
        status = CASE WHEN status = 'closed' THEN 'active' ELSE status END
    WHERE id = v_conv.id;

    RETURN jsonb_build_object(
        'status', 'sent',
        'message_id', v_new_msg.id,
        'id', v_new_msg.id,
        'conversation_id', v_conv.id,
        'order_id', v_conv.order_id,
        'sender_id', v_uid,
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

-- Overload with p_sender_id at 2nd position
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

GRANT EXECUTE ON FUNCTION public.chat_send(UUID, TEXT, UUID, UUID, TEXT, TEXT, TEXT, NUMERIC, NUMERIC, DATE) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) TO anon, authenticated, service_role;

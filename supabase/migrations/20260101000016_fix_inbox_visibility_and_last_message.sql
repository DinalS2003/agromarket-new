-- AgroMarket Migration 16: Fix Inbox Visibility and Last Message Linking
-- Ensures conversations and their newest messages are immediately visible in the messages tab
-- with recent messages at the top.

BEGIN;

-- 1. Update chat_send to explicitly store last_message_id and un-delete for both parties
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
    v_existing RECORD;
    v_new_msg RECORD;
    v_clean_body TEXT;
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
        attachment_path,
        attachment_url,
        offer_price,
        offer_quantity,
        offer_date,
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
        NOW()
    )
    RETURNING * INTO v_new_msg;

    -- Update conversation last message id, preview, timestamp, and un-delete
    UPDATE public.conversations
    SET last_message_id = v_new_msg.id,
        last_message_at = v_new_msg.created_at,
        last_message_preview = v_clean_body,
        last_message_sender_id = v_uid,
        deleted_by_buyer = FALSE,
        deleted_by_seller = FALSE,
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

-- 2. Enhanced get_inbox with resilient latest message fallback
DROP FUNCTION IF EXISTS public.get_inbox(INT, TIMESTAMPTZ, UUID) CASCADE;
DROP FUNCTION IF EXISTS public.get_inbox(INT, TIMESTAMPTZ) CASCADE;
DROP FUNCTION IF EXISTS public.get_inbox() CASCADE;

CREATE OR REPLACE FUNCTION public.get_inbox(
    p_limit INT DEFAULT 50,
    p_cursor TIMESTAMPTZ DEFAULT NULL,
    p_user_id UUID DEFAULT NULL
)
RETURNS TABLE (
    conversation_id UUID,
    listing_id UUID,
    crop_name TEXT,
    listing_thumbnail TEXT,
    price_per_kg NUMERIC,
    counterpart_id UUID,
    counterpart_name TEXT,
    counterpart_avatar TEXT,
    is_counterpart_farmer BOOLEAN,
    last_message_id UUID,
    last_message_preview TEXT,
    last_message_sender_id UUID,
    last_message_at TIMESTAMPTZ,
    unread_count BIGINT,
    order_id UUID,
    order_number TEXT,
    order_status TEXT,
    status TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := COALESCE(p_user_id, auth.uid());
BEGIN
    IF v_uid IS NULL THEN
        RETURN;
    END IF;

    RETURN QUERY
    WITH user_convs AS (
        SELECT
            c.id AS c_id,
            c.listing_id AS c_listing_id,
            c.buyer_id AS c_buyer_id,
            c.seller_id AS c_seller_id,
            c.order_id AS c_order_id,
            c.status AS c_status,
            c.last_message_id AS c_last_msg_id,
            c.last_message_preview AS c_last_msg_preview,
            c.last_message_sender_id AS c_last_msg_sender_id,
            c.last_message_at AS c_last_msg_at
        FROM public.conversations c
        WHERE (
            -- Strictly show only conversations where this user is an engaged participant
            (c.buyer_id = v_uid AND (c.deleted_by_buyer IS NOT TRUE))
            OR 
            (c.seller_id = v_uid AND (c.deleted_by_seller IS NOT TRUE))
        )
        AND (p_cursor IS NULL OR c.last_message_at < p_cursor)
        ORDER BY c.last_message_at DESC NULLS LAST
        LIMIT LEAST(p_limit, 100)
    ),
    latest_messages AS (
        -- Fallback to actual latest message if last_message_id was null
        SELECT DISTINCT ON (m.conversation_id)
            m.conversation_id,
            m.id AS msg_id,
            m.body AS msg_body,
            m.sender_id AS msg_sender_id,
            m.created_at AS msg_created_at
        FROM public.messages m
        INNER JOIN user_convs uc ON uc.c_id = m.conversation_id
        ORDER BY m.conversation_id, m.created_at DESC
    ),
    unread_stats AS (
        SELECT
            uc.c_id,
            COUNT(m.id) AS unreads
        FROM user_convs uc
        LEFT JOIN public.chat_reads cr
            ON cr.conversation_id = uc.c_id AND cr.user_id = v_uid
        LEFT JOIN public.messages read_m
            ON read_m.id = cr.last_read_message_id
        LEFT JOIN public.messages m
            ON m.conversation_id = uc.c_id
            AND m.kind != 'system'
            AND m.sender_id IS NOT NULL
            AND m.sender_id != v_uid
            AND (
                cr.last_read_message_id IS NULL
                OR m.created_at > COALESCE(read_m.created_at, cr.last_read_at, '1970-01-01'::timestamptz)
            )
        GROUP BY uc.c_id
    )
    SELECT
        uc.c_id AS conversation_id,
        uc.c_listing_id AS listing_id,
        COALESCE(l.crop_name, o.crop_name, 'Produce') AS crop_name,
        CASE
            WHEN l.photos IS NOT NULL AND array_length(l.photos, 1) > 0 THEN l.photos[1]
            ELSE NULL
        END AS listing_thumbnail,
        COALESCE(l.price_per_kg, o.price_per_kg, 0.0) AS price_per_kg,
        CASE WHEN v_uid = uc.c_buyer_id THEN uc.c_seller_id ELSE uc.c_buyer_id END AS counterpart_id,
        COALESCE(p.full_name, CASE WHEN v_uid = uc.c_buyer_id THEN 'Farmer' ELSE 'Buyer' END) AS counterpart_name,
        p.avatar_url AS counterpart_avatar,
        (CASE WHEN v_uid = uc.c_buyer_id THEN true ELSE false END) AS is_counterpart_farmer,
        COALESCE(uc.c_last_msg_id, lm_fallback.msg_id) AS last_message_id,
        COALESCE(lm.body, lm_fallback.msg_body, uc.c_last_msg_preview, 'No messages yet') AS last_message_preview,
        COALESCE(lm.sender_id, lm_fallback.msg_sender_id, uc.c_last_msg_sender_id) AS last_message_sender_id,
        COALESCE(uc.c_last_msg_at, lm_fallback.msg_created_at, NOW()) AS last_message_at,
        COALESCE(us.unreads, 0) AS unread_count,
        o.id AS order_id,
        o.order_number,
        o.status AS order_status,
        uc.c_status AS status
    FROM user_convs uc
    LEFT JOIN public.listings l ON l.id = uc.c_listing_id
    LEFT JOIN public.orders o ON o.id = uc.c_order_id
    LEFT JOIN public.profiles p ON p.id = (CASE WHEN v_uid = uc.c_buyer_id THEN uc.c_seller_id ELSE uc.c_buyer_id END)
    LEFT JOIN public.messages lm ON lm.id = uc.c_last_msg_id
    LEFT JOIN latest_messages lm_fallback ON lm_fallback.conversation_id = uc.c_id
    LEFT JOIN unread_stats us ON us.c_id = uc.c_id
    ORDER BY COALESCE(uc.c_last_msg_at, lm_fallback.msg_created_at) DESC NULLS LAST;
END;
$$;

CREATE OR REPLACE FUNCTION public.get_inbox(
    p_limit INT DEFAULT 50,
    p_cursor TIMESTAMPTZ DEFAULT NULL
)
RETURNS TABLE (
    conversation_id UUID,
    listing_id UUID,
    crop_name TEXT,
    listing_thumbnail TEXT,
    price_per_kg NUMERIC,
    counterpart_id UUID,
    counterpart_name TEXT,
    counterpart_avatar TEXT,
    is_counterpart_farmer BOOLEAN,
    last_message_id UUID,
    last_message_preview TEXT,
    last_message_sender_id UUID,
    last_message_at TIMESTAMPTZ,
    unread_count BIGINT,
    order_id UUID,
    order_number TEXT,
    order_status TEXT,
    status TEXT
)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
BEGIN
    RETURN QUERY SELECT * FROM public.get_inbox(p_limit, p_cursor, NULL);
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_inbox(INT, TIMESTAMPTZ, UUID) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_inbox(INT, TIMESTAMPTZ) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_inbox() TO anon, authenticated, service_role;

COMMIT;

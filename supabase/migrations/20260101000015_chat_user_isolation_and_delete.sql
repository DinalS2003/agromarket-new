-- AgroMarket Migration 15: Chat User Isolation and Conversation Deletion
-- Ensures users only see their own engaged chats, recent messages are at the top,
-- and users can delete their conversations.

BEGIN;

-- 1. Add deletion tracking columns to conversations table
ALTER TABLE public.conversations
    ADD COLUMN IF NOT EXISTS deleted_by_buyer BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS deleted_by_seller BOOLEAN DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS buyer_deleted_at TIMESTAMPTZ DEFAULT NULL,
    ADD COLUMN IF NOT EXISTS seller_deleted_at TIMESTAMPTZ DEFAULT NULL;

CREATE INDEX IF NOT EXISTS idx_conversations_deleted_buyer
    ON public.conversations(buyer_id, deleted_by_buyer, last_message_at DESC);

CREATE INDEX IF NOT EXISTS idx_conversations_deleted_seller
    ON public.conversations(seller_id, deleted_by_seller, last_message_at DESC);

-- 2. RPC: delete_conversation(p_conversation_id, p_user_id)
CREATE OR REPLACE FUNCTION public.delete_conversation(
    p_conversation_id UUID,
    p_user_id UUID DEFAULT NULL
)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := COALESCE(p_user_id, auth.uid());
    v_conv RECORD;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    -- Lookup conversation by ID or by linked order ID
    SELECT id, buyer_id, seller_id, deleted_by_buyer, deleted_by_seller
    INTO v_conv
    FROM public.conversations
    WHERE id = p_conversation_id;

    IF NOT FOUND THEN
        SELECT id, buyer_id, seller_id, deleted_by_buyer, deleted_by_seller
        INTO v_conv
        FROM public.conversations
        WHERE order_id = p_conversation_id;
    END IF;

    IF NOT FOUND THEN
        RETURN TRUE; -- Already gone or doesn't exist
    END IF;

    -- Verify user is a party or admin
    IF v_conv.buyer_id != v_uid AND v_conv.seller_id != v_uid THEN
        IF NOT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = v_uid) THEN
            RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    -- Mark deletion for this participant
    IF v_uid = v_conv.buyer_id THEN
        UPDATE public.conversations
        SET deleted_by_buyer = TRUE,
            buyer_deleted_at = NOW()
        WHERE id = v_conv.id;
    ELSIF v_uid = v_conv.seller_id THEN
        UPDATE public.conversations
        SET deleted_by_seller = TRUE,
            seller_deleted_at = NOW()
        WHERE id = v_conv.id;
    ELSE
        -- Admin: delete for both
        UPDATE public.conversations
        SET deleted_by_buyer = TRUE,
            deleted_by_seller = TRUE,
            buyer_deleted_at = NOW(),
            seller_deleted_at = NOW()
        WHERE id = v_conv.id;
    END IF;

    -- If both parties have deleted the conversation, purge messages and conversation completely
    IF EXISTS (
        SELECT 1 FROM public.conversations
        WHERE id = v_conv.id AND deleted_by_buyer IS TRUE AND deleted_by_seller IS TRUE
    ) THEN
        DELETE FROM public.messages WHERE conversation_id = v_conv.id;
        DELETE FROM public.conversations WHERE id = v_conv.id;
    END IF;

    RETURN TRUE;
END;
$$;

GRANT EXECUTE ON FUNCTION public.delete_conversation(UUID, UUID) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.delete_conversation(UUID) TO anon, authenticated, service_role;

-- 3. Update get_inbox with optional p_user_id and filter out deleted chats
DROP FUNCTION IF EXISTS public.get_inbox(INT, TIMESTAMPTZ) CASCADE;
DROP FUNCTION IF EXISTS public.get_inbox(INT, TIMESTAMPTZ, UUID) CASCADE;

CREATE OR REPLACE FUNCTION public.get_inbox(
    p_limit INT DEFAULT 30,
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
        -- If no user is identified, return an empty set rather than throwing or leaking
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
            c.last_message_at AS c_last_msg_at
        FROM public.conversations c
        WHERE (
            -- Strictly show only conversations where this user is an engaged participant
            (c.buyer_id = v_uid AND (c.deleted_by_buyer IS NOT TRUE))
            OR 
            (c.seller_id = v_uid AND (c.deleted_by_seller IS NOT TRUE))
        )
        AND (p_cursor IS NULL OR c.last_message_at < p_cursor)
        ORDER BY c.last_message_at DESC
        LIMIT LEAST(p_limit, 100)
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
        COALESCE(p.full_name, 'User') AS counterpart_name,
        NULL::TEXT AS counterpart_avatar,
        (CASE WHEN v_uid = uc.c_buyer_id THEN true ELSE false END) AS is_counterpart_farmer,
        uc.c_last_msg_id AS last_message_id,
        COALESCE(lm.body, 'No messages yet') AS last_message_preview,
        lm.sender_id AS last_message_sender_id,
        uc.c_last_msg_at AS last_message_at,
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
    LEFT JOIN unread_stats us ON us.c_id = uc.c_id
    ORDER BY uc.c_last_msg_at DESC;
END;
$$;

-- Overload with 2 arguments for backwards compatibility
CREATE OR REPLACE FUNCTION public.get_inbox(
    p_limit INT DEFAULT 30,
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

-- 4. Update get_conversation_messages to support p_user_id and respect deletion
CREATE OR REPLACE FUNCTION public.get_conversation_messages(
    p_conversation_id UUID,
    p_before_created_at TIMESTAMPTZ DEFAULT NULL,
    p_before_id UUID DEFAULT NULL,
    p_limit INT DEFAULT 30,
    p_user_id UUID DEFAULT NULL
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
    v_uid UUID := COALESCE(p_user_id, auth.uid());
    v_conv RECORD;
    v_deleted_at TIMESTAMPTZ;
BEGIN
    IF v_uid IS NULL THEN
        RETURN;
    END IF;

    SELECT c.id, c.buyer_id, c.seller_id, c.order_id, c.deleted_by_buyer, c.deleted_by_seller, c.buyer_deleted_at, c.seller_deleted_at
    INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id, c.order_id, c.deleted_by_buyer, c.deleted_by_seller, c.buyer_deleted_at, c.seller_deleted_at
        INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id;
    END IF;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id, c.order_id, c.deleted_by_buyer, c.deleted_by_seller, c.buyer_deleted_at, c.seller_deleted_at
        INTO v_conv
        FROM public.conversations c
        WHERE c.listing_id = p_conversation_id
          AND (c.buyer_id = v_uid OR c.seller_id = v_uid)
        ORDER BY c.created_at DESC
        LIMIT 1;
    END IF;

    IF NOT FOUND THEN
        RETURN;
    END IF;

    -- Strict authorization check: must be buyer or seller
    IF v_conv.buyer_id != v_uid AND v_conv.seller_id != v_uid THEN
        IF NOT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = v_uid) THEN
            RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    -- If this user deleted the conversation, only show messages newer than deletion timestamp
    IF v_uid = v_conv.buyer_id AND v_conv.deleted_by_buyer IS TRUE THEN
        v_deleted_at := v_conv.buyer_deleted_at;
    ELSIF v_uid = v_conv.seller_id AND v_conv.deleted_by_seller IS TRUE THEN
        v_deleted_at := v_conv.seller_deleted_at;
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
      AND (v_deleted_at IS NULL OR m.created_at > v_deleted_at)
      AND (
          p_before_created_at IS NULL
          OR (m.created_at, m.id) < (p_before_created_at, p_before_id)
      )
    ORDER BY m.created_at DESC, m.id DESC
    LIMIT LEAST(p_limit, 50);
END;
$$;

-- Overload with 4 arguments for backwards compatibility
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
BEGIN
    RETURN QUERY SELECT * FROM public.get_conversation_messages(p_conversation_id, p_before_created_at, p_before_id, p_limit, NULL);
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_conversation_messages(UUID, TIMESTAMPTZ, UUID, INT, UUID) TO anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_conversation_messages(UUID, TIMESTAMPTZ, UUID, INT) TO anon, authenticated, service_role;

COMMIT;

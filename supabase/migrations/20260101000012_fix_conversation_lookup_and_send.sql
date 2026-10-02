-- Migration: 20260101000012_fix_conversation_lookup_and_send.sql
-- Description: Fix CONVERSATION_NOT_FOUND (P0001) on new chats and provide direct PostgREST RPC chat_send

BEGIN;

-- ============================================================================
-- 1. ENHANCE get_conversation_messages TO SUPPORT LISTING_ID & RETURN EMPTY INSTEAD OF 400
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
    sender_role TEXT
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

    -- 1. Try finding conversation by conversation ID
    SELECT c.id, c.buyer_id, c.seller_id, c.order_id INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id;

    -- 2. Try finding conversation by order ID
    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id, c.order_id INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id;
    END IF;

    -- 3. Try finding conversation by listing ID where caller is buyer or seller
    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id, c.order_id INTO v_conv
        FROM public.conversations c
        WHERE c.listing_id = p_conversation_id
          AND (c.buyer_id = v_uid OR c.seller_id = v_uid)
        ORDER BY c.created_at DESC
        LIMIT 1;
    END IF;

    -- 4. If conversation still not found, return empty set rather than throwing P0001
    IF NOT FOUND THEN
        RETURN;
    END IF;

    -- Check caller is a participant or admin
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
        END AS sender_role
    FROM public.messages m
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

-- ============================================================================
-- 2. ENHANCE get_messages_since TO SUPPORT LISTING_ID & RETURN EMPTY INSTEAD OF 400
-- ============================================================================
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
    sender_role TEXT
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
        END AS sender_role
    FROM public.messages m
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

-- ============================================================================
-- 3. ROBUST PostgREST RPC: chat_send(p_conversation_id, p_sender_id, p_body, p_client_nonce)
-- ============================================================================
DROP FUNCTION IF EXISTS public.chat_send(UUID, UUID, TEXT, UUID) CASCADE;
DROP FUNCTION IF EXISTS public.chat_send(UUID, TEXT, UUID) CASCADE;

CREATE OR REPLACE FUNCTION public.chat_send(
    p_conversation_id UUID,
    p_body TEXT,
    p_client_nonce UUID DEFAULT NULL,
    p_sender_id UUID DEFAULT NULL
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

    -- Check conversation not blocked or closed
    IF v_conv.status IN ('closed', 'blocked') THEN
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
        SELECT id, conversation_id, order_id, sender_id, kind, body, client_nonce, created_at
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
        created_at
    ) VALUES (
        v_conv.id,
        v_conv.order_id,
        v_uid,
        'user',
        v_clean_body,
        p_client_nonce,
        NOW()
    )
    RETURNING id, conversation_id, order_id, sender_id, kind, body, client_nonce, created_at
    INTO v_new_msg;

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
    v_recipient_id := CASE WHEN v_uid = v_conv.buyer_id THEN v_conv.seller_id ELSE v_conv.buyer_id END;

    IF v_conv.listing_id IS NOT NULL THEN
        SELECT crop_name INTO v_crop_name FROM public.listings WHERE id = v_conv.listing_id;
    END IF;

    INSERT INTO public.notifications (user_id, type, title, body, order_id, created_at)
    VALUES (
        v_recipient_id,
        'chat_message',
        'New message regarding ' || COALESCE(v_crop_name, 'Produce'),
        COALESCE(v_sender_name, 'User') || ': ' || LEFT(v_clean_body, 60),
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
        'created_at', v_new_msg.created_at
    );
END;
$$;

-- Provide overload with p_sender_id at 2nd position for backward compatibility
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

REVOKE ALL ON FUNCTION public.chat_send(UUID, TEXT, UUID, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.chat_send(UUID, TEXT, UUID, UUID) TO authenticated, service_role;
REVOKE ALL ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) TO authenticated, service_role;

-- Ensure RLS on messages allows authenticated insert and select
ALTER TABLE public.messages ENABLE ROW LEVEL SECURITY;
GRANT SELECT, INSERT ON public.messages TO authenticated;

COMMIT;

-- Migration: 20260101000011_receipts_and_presence.sql
-- Description: Delivery & read receipts, gap recovery RPC, unread count excluding system messages, and RLS

BEGIN;

-- ============================================================================
-- 1. EXTEND CHAT_READS FOR DELIVERY AND READ RECEIPTS
-- ============================================================================

ALTER TABLE public.chat_reads
    ADD COLUMN IF NOT EXISTS last_read_message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS last_delivered_message_id UUID REFERENCES public.messages(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS last_delivered_at TIMESTAMPTZ DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_chat_reads_read_msg ON public.chat_reads(last_read_message_id);
CREATE INDEX IF NOT EXISTS idx_chat_reads_deliv_msg ON public.chat_reads(last_delivered_message_id);

-- Ensure chat_reads is in realtime publication so receipt ticks update in realtime
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND tablename = 'chat_reads'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.chat_reads;
    END IF;
END $$;

-- ============================================================================
-- 2. RPC: mark_conversation_read(conversation_id, message_id)
-- ============================================================================
DROP FUNCTION IF EXISTS public.mark_conversation_read(UUID) CASCADE;
DROP FUNCTION IF EXISTS public.mark_conversation_read(UUID, UUID) CASCADE;

CREATE OR REPLACE FUNCTION public.mark_conversation_read(
    p_conversation_id UUID,
    p_message_id UUID DEFAULT NULL
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_latest_msg_id UUID := p_message_id;
    v_order_id UUID;
BEGIN
    IF v_uid IS NULL THEN
        RETURN;
    END IF;

    -- Resolve conversation if order_id was provided
    IF NOT EXISTS (SELECT 1 FROM public.conversations WHERE id = p_conversation_id) THEN
        SELECT id, order_id INTO p_conversation_id, v_order_id FROM public.conversations WHERE order_id = p_conversation_id;
        IF p_conversation_id IS NULL THEN
            RETURN;
        END IF;
    ELSE
        SELECT order_id INTO v_order_id FROM public.conversations WHERE id = p_conversation_id;
    END IF;

    -- Find newest message ID if not explicitly specified
    IF v_latest_msg_id IS NULL THEN
        SELECT id INTO v_latest_msg_id
        FROM public.messages
        WHERE conversation_id = p_conversation_id
        ORDER BY created_at DESC, id DESC
        LIMIT 1;
    END IF;

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
        p_conversation_id,
        v_order_id,
        v_uid,
        v_latest_msg_id,
        NOW(),
        v_latest_msg_id,
        NOW()
    )
    ON CONFLICT (conversation_id, user_id)
    DO UPDATE SET
        last_read_message_id = COALESCE(v_latest_msg_id, chat_reads.last_read_message_id),
        last_read_at = NOW(),
        last_delivered_message_id = COALESCE(v_latest_msg_id, chat_reads.last_delivered_message_id),
        last_delivered_at = NOW();
END;
$$;

REVOKE ALL ON FUNCTION public.mark_conversation_read(UUID, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mark_conversation_read(UUID, UUID) TO authenticated, service_role;

-- ============================================================================
-- 3. RPC: mark_messages_delivered(conversation_id, message_id)
-- ============================================================================
DROP FUNCTION IF EXISTS public.mark_messages_delivered(UUID, UUID) CASCADE;

CREATE OR REPLACE FUNCTION public.mark_messages_delivered(
    p_conversation_id UUID,
    p_message_id UUID
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_order_id UUID;
BEGIN
    IF v_uid IS NULL OR p_message_id IS NULL THEN
        RETURN;
    END IF;

    -- Resolve conversation if order_id was provided
    IF NOT EXISTS (SELECT 1 FROM public.conversations WHERE id = p_conversation_id) THEN
        SELECT id, order_id INTO p_conversation_id, v_order_id FROM public.conversations WHERE order_id = p_conversation_id;
        IF p_conversation_id IS NULL THEN
            RETURN;
        END IF;
    ELSE
        SELECT order_id INTO v_order_id FROM public.conversations WHERE id = p_conversation_id;
    END IF;

    INSERT INTO public.chat_reads (
        conversation_id,
        order_id,
        user_id,
        last_delivered_message_id,
        last_delivered_at
    )
    VALUES (
        p_conversation_id,
        v_order_id,
        v_uid,
        p_message_id,
        NOW()
    )
    ON CONFLICT (conversation_id, user_id)
    DO UPDATE SET
        last_delivered_message_id = p_message_id,
        last_delivered_at = NOW();
END;
$$;

REVOKE ALL ON FUNCTION public.mark_messages_delivered(UUID, UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mark_messages_delivered(UUID, UUID) TO authenticated, service_role;

-- ============================================================================
-- 4. RPC: get_conversation_receipts(conversation_id)
-- ============================================================================
DROP FUNCTION IF EXISTS public.get_conversation_receipts(UUID) CASCADE;

CREATE OR REPLACE FUNCTION public.get_conversation_receipts(
    p_conversation_id UUID
)
RETURNS TABLE (
    user_id UUID,
    last_read_message_id UUID,
    last_read_at TIMESTAMPTZ,
    last_delivered_message_id UUID,
    last_delivered_at TIMESTAMPTZ
)
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

    -- Ensure caller is a participant or admin
    IF NOT EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.id = p_conversation_id
          AND (c.buyer_id = v_uid OR c.seller_id = v_uid)
    ) AND NOT EXISTS (
        SELECT 1 FROM public.admin_users WHERE user_id = v_uid
    ) THEN
        RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
    END IF;

    RETURN QUERY
    SELECT
        cr.user_id,
        cr.last_read_message_id,
        cr.last_read_at,
        cr.last_delivered_message_id,
        cr.last_delivered_at
    FROM public.chat_reads cr
    WHERE cr.conversation_id = p_conversation_id;
END;
$$;

REVOKE ALL ON FUNCTION public.get_conversation_receipts(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_conversation_receipts(UUID) TO authenticated, service_role;

-- ============================================================================
-- 5. RPC: get_messages_since(conversation_id, after_created_at, after_id)
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
        RAISE EXCEPTION 'NOT_AUTHENTICATED' USING ERRCODE = '42501';
    END IF;

    SELECT c.id, c.buyer_id, c.seller_id INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id;

    IF NOT FOUND THEN
        SELECT c.id, c.buyer_id, c.seller_id INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id;

        IF FOUND THEN
            p_conversation_id := v_conv.id;
        ELSE
            RAISE EXCEPTION 'CONVERSATION_NOT_FOUND' USING ERRCODE = 'P0001';
        END IF;
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
    WHERE m.conversation_id = p_conversation_id
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
-- 6. RPC: get_total_unread_count() EXCLUDING SYSTEM MESSAGES
-- ============================================================================
DROP FUNCTION IF EXISTS public.get_total_unread_count() CASCADE;

CREATE OR REPLACE FUNCTION public.get_total_unread_count()
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_total BIGINT := 0;
BEGIN
    IF v_uid IS NULL THEN
        RETURN 0;
    END IF;

    SELECT COALESCE(COUNT(m.id), 0) INTO v_total
    FROM public.conversations c
    JOIN public.messages m ON m.conversation_id = c.id
    LEFT JOIN public.chat_reads cr ON cr.conversation_id = c.id AND cr.user_id = v_uid
    LEFT JOIN public.messages read_m ON read_m.id = cr.last_read_message_id
    WHERE (c.buyer_id = v_uid OR c.seller_id = v_uid)
      AND m.kind != 'system'
      AND m.sender_id IS NOT NULL
      AND m.sender_id != v_uid
      AND (
          cr.last_read_message_id IS NULL
          OR m.created_at > COALESCE(read_m.created_at, cr.last_read_at, '1970-01-01'::timestamptz)
      );

    RETURN v_total;
END;
$$;

REVOKE ALL ON FUNCTION public.get_total_unread_count() FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_total_unread_count() TO authenticated, service_role;

-- ============================================================================
-- 7. UPDATE GET_INBOX TO COMPUTE UNREADS EXCLUDING SYSTEM MESSAGES
-- ============================================================================
DROP FUNCTION IF EXISTS public.get_inbox(INT, TIMESTAMPTZ) CASCADE;

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
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED: Must be logged in to view inbox' USING ERRCODE = '42501';
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
        WHERE (c.buyer_id = v_uid OR c.seller_id = v_uid)
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

REVOKE ALL ON FUNCTION public.get_inbox(INT, TIMESTAMPTZ) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_inbox(INT, TIMESTAMPTZ) TO authenticated, service_role;

-- ============================================================================
-- 8. ROW LEVEL SECURITY (RLS) FOR CHAT_READS
-- ============================================================================
ALTER TABLE public.chat_reads ENABLE ROW LEVEL SECURITY;

DROP POLICY IF EXISTS "chat_reads_select_parties" ON public.chat_reads;
CREATE POLICY "chat_reads_select_parties" ON public.chat_reads
FOR SELECT TO authenticated
USING (
    user_id = auth.uid()
    OR EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.id = chat_reads.conversation_id
          AND (c.buyer_id = auth.uid() OR c.seller_id = auth.uid())
    )
    OR EXISTS (
        SELECT 1 FROM public.admin_users WHERE user_id = auth.uid()
    )
);

DROP POLICY IF EXISTS "chat_reads_modify_own" ON public.chat_reads;
CREATE POLICY "chat_reads_modify_own" ON public.chat_reads
FOR ALL TO authenticated
USING (user_id = auth.uid())
WITH CHECK (user_id = auth.uid());

GRANT SELECT, INSERT, UPDATE ON public.chat_reads TO authenticated;
GRANT ALL ON public.chat_reads TO service_role, postgres;

COMMIT;

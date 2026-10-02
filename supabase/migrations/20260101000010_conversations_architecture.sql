-- Migration: 20260101000010_conversations_architecture.sql
-- Description:
--   1. Replaces "inquiry as fake order" with a dedicated public.conversations table.
--   2. Structure: conversations(id, listing_id, buyer_id, seller_id, order_id nullable,
--                 status active/closed/blocked, last_message_id, last_message_at, created_at)
--                 with UNIQUE(listing_id, buyer_id).
--   3. Updates messages to reference conversation_id (UUID) while keeping order_id nullable.
--   4. Backfills conversations and messages from existing orders & inquiries.
--   5. Eliminates status='inquiry' from public.orders and updates orders check constraint.
--   6. Implements get_or_create_conversation(listing_id) RPC (auth.uid() only, no self-messaging, rate-limited per day).
--   7. Implements get_inbox(limit, cursor) RPC returning counterpart display name, avatar,
--      listing thumbnail, last message preview, unread count, order status and conversation status in ONE query.
--   8. Links conversations to orders upon real order creation, and triggers system messages on status updates.
--   9. Strictly secures RLS for parties + admins (default deny).

BEGIN;

-- ============================================================================
-- 1. CREATE CONVERSATIONS TABLE
-- ============================================================================
CREATE TABLE IF NOT EXISTS public.conversations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_id UUID NOT NULL REFERENCES public.listings(id) ON DELETE CASCADE,
    buyer_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    seller_id UUID NOT NULL REFERENCES public.profiles(id) ON DELETE CASCADE,
    order_id UUID REFERENCES public.orders(id) ON DELETE SET NULL,
    status TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'closed', 'blocked')),
    last_message_id UUID,
    last_message_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_conversations_listing_buyer UNIQUE (listing_id, buyer_id)
);

CREATE INDEX IF NOT EXISTS idx_conversations_buyer ON public.conversations(buyer_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_seller ON public.conversations(seller_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_order ON public.conversations(order_id);
CREATE INDEX IF NOT EXISTS idx_conversations_listing ON public.conversations(listing_id);

-- ============================================================================
-- 2. UPDATE MESSAGES AND CHAT TABLES TO REFERENCE CONVERSATION_ID
-- ============================================================================
ALTER TABLE public.messages
    ADD COLUMN IF NOT EXISTS conversation_id UUID REFERENCES public.conversations(id) ON DELETE CASCADE;

ALTER TABLE public.messages
    ALTER COLUMN order_id DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_messages_conv_created
    ON public.messages(conversation_id, created_at DESC, id DESC);

CREATE UNIQUE INDEX IF NOT EXISTS idx_messages_conv_sender_nonce
    ON public.messages(conversation_id, sender_id, client_nonce)
    WHERE client_nonce IS NOT NULL;

-- Drop composite PK on chat_reads (order_id, user_id) if it exists so order_id can be nullable
DO $$
DECLARE
    v_pk_name TEXT;
BEGIN
    SELECT conname INTO v_pk_name
    FROM pg_constraint
    WHERE conrelid = 'public.chat_reads'::regclass AND contype = 'p';

    IF v_pk_name IS NOT NULL THEN
        EXECUTE 'ALTER TABLE public.chat_reads DROP CONSTRAINT ' || quote_ident(v_pk_name);
    END IF;
END $$;

ALTER TABLE public.chat_reads
    ADD COLUMN IF NOT EXISTS id UUID DEFAULT gen_random_uuid();

UPDATE public.chat_reads SET id = gen_random_uuid() WHERE id IS NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.chat_reads'::regclass AND contype = 'p'
    ) THEN
        ALTER TABLE public.chat_reads ADD PRIMARY KEY (id);
    END IF;
END $$;

ALTER TABLE public.chat_reads
    ADD COLUMN IF NOT EXISTS conversation_id UUID REFERENCES public.conversations(id) ON DELETE CASCADE;

ALTER TABLE public.chat_reads
    ALTER COLUMN order_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_reads_conv_user
    ON public.chat_reads(conversation_id, user_id)
    WHERE conversation_id IS NOT NULL;

ALTER TABLE public.flagged_messages
    ADD COLUMN IF NOT EXISTS conversation_id UUID REFERENCES public.conversations(id) ON DELETE CASCADE;

ALTER TABLE public.flagged_messages
    ALTER COLUMN order_id DROP NOT NULL;

-- ============================================================================
-- 3. DATA BACKFILL
-- ============================================================================

-- Backfill conversations from existing orders
INSERT INTO public.conversations (
    listing_id,
    buyer_id,
    seller_id,
    order_id,
    status,
    created_at,
    last_message_at
)
SELECT DISTINCT ON (o.listing_id, o.buyer_id)
    o.listing_id,
    o.buyer_id,
    o.farmer_id,
    CASE WHEN o.status = 'inquiry' THEN NULL ELSE o.id END,
    'active',
    o.created_at,
    COALESCE(
        (SELECT MAX(m.created_at) FROM public.messages m WHERE m.order_id = o.id),
        o.created_at
    )
FROM public.orders o
WHERE o.listing_id IS NOT NULL AND o.buyer_id IS NOT NULL AND o.farmer_id IS NOT NULL
ORDER BY o.listing_id, o.buyer_id, o.created_at DESC
ON CONFLICT (listing_id, buyer_id) DO UPDATE SET
    order_id = COALESCE(conversations.order_id, EXCLUDED.order_id);

-- Backfill messages conversation_id
UPDATE public.messages m
SET conversation_id = c.id
FROM public.orders o
JOIN public.conversations c ON c.listing_id = o.listing_id AND c.buyer_id = o.buyer_id
WHERE m.order_id = o.id AND m.conversation_id IS NULL;

-- Backfill conversations last_message_id and last_message_at
UPDATE public.conversations c
SET last_message_id = lm.id,
    last_message_at = lm.created_at
FROM (
    SELECT DISTINCT ON (conversation_id) id, conversation_id, created_at
    FROM public.messages
    WHERE conversation_id IS NOT NULL
    ORDER BY conversation_id, created_at DESC, id DESC
) lm
WHERE c.id = lm.conversation_id;

-- Backfill chat_reads conversation_id
UPDATE public.chat_reads cr
SET conversation_id = c.id
FROM public.orders o
JOIN public.conversations c ON c.listing_id = o.listing_id AND c.buyer_id = o.buyer_id
WHERE cr.order_id = o.id AND cr.conversation_id IS NULL;

-- Deduplicate chat_reads on (conversation_id, user_id)
DELETE FROM public.chat_reads a
USING public.chat_reads b
WHERE a.id < b.id
  AND a.conversation_id IS NOT NULL
  AND a.conversation_id = b.conversation_id
  AND a.user_id = b.user_id;

-- Add unique constraint on (conversation_id, user_id) for ON CONFLICT (conversation_id, user_id)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.chat_reads'::regclass AND conname = 'uq_chat_reads_conv_user'
    ) THEN
        ALTER TABLE public.chat_reads ADD CONSTRAINT uq_chat_reads_conv_user UNIQUE (conversation_id, user_id);
    END IF;
END $$;

-- Remove fake status='inquiry' orders
DELETE FROM public.orders WHERE status = 'inquiry';

-- Restore strict orders check constraint without 'inquiry'
ALTER TABLE public.orders DROP CONSTRAINT IF EXISTS orders_status_check;
ALTER TABLE public.orders ADD CONSTRAINT orders_status_check CHECK (
    status = ANY (ARRAY[
        'requested'::text, 'accepted'::text, 'rejected'::text,
        'paid'::text, 'ready'::text, 'dispatched'::text, 'delivered'::text,
        'completed'::text, 'cancelled'::text, 'expired'::text, 'refunded'::text, 'disputed'::text
    ])
);

-- ============================================================================
-- 4. ROW LEVEL SECURITY (RLS) FOR CONVERSATIONS & MESSAGES
-- ============================================================================
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
REVOKE ALL ON public.conversations FROM PUBLIC, anon;
GRANT SELECT ON public.conversations TO authenticated, service_role;
GRANT ALL ON public.conversations TO service_role, postgres;

DROP POLICY IF EXISTS "conversations_select_parties_and_admin" ON public.conversations;
CREATE POLICY "conversations_select_parties_and_admin" ON public.conversations
FOR SELECT TO authenticated
USING (
    auth.uid() = buyer_id
    OR auth.uid() = seller_id
    OR EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = auth.uid())
);

-- Update messages select policy to check conversation parties
DROP POLICY IF EXISTS "messages_select_parties_and_admin" ON public.messages;
CREATE POLICY "messages_select_parties_and_admin" ON public.messages
FOR SELECT TO authenticated
USING (
    EXISTS (
        SELECT 1 FROM public.conversations c
        WHERE c.id = messages.conversation_id
          AND (c.buyer_id = auth.uid() OR c.seller_id = auth.uid())
    )
    OR (
        messages.order_id IS NOT NULL
        AND EXISTS (
            SELECT 1 FROM public.orders o
            WHERE o.id = messages.order_id
              AND (o.buyer_id = auth.uid() OR o.farmer_id = auth.uid())
        )
    )
    OR EXISTS (
        SELECT 1 FROM public.admin_users a
        WHERE a.user_id = auth.uid()
    )
);

-- Ensure client mutations on messages remain strictly revoked
REVOKE INSERT, UPDATE, DELETE ON public.messages FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.messages TO authenticated, service_role;
GRANT ALL ON public.messages TO service_role, postgres;

-- ============================================================================
-- 5. RPC: get_or_create_conversation(listing_id)
-- ============================================================================
DROP FUNCTION IF EXISTS public.get_or_create_conversation(UUID) CASCADE;
CREATE OR REPLACE FUNCTION public.get_or_create_conversation(
    p_listing_id UUID
)
RETURNS UUID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_listing RECORD;
    v_conv_id UUID;
    v_created_24h INT;
    v_order_id UUID;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'NOT_AUTHENTICATED: Authentication required to start a conversation' USING ERRCODE = '42501';
    END IF;

    -- Fetch crop listing
    SELECT id, farmer_id, crop_name INTO v_listing
    FROM public.listings
    WHERE id = p_listing_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'LISTING_NOT_FOUND: The specified crop listing does not exist' USING ERRCODE = 'P0001';
    END IF;

    -- Prevent messaging own listing
    IF v_listing.farmer_id = v_uid THEN
        RAISE EXCEPTION 'CANNOT_MESSAGE_OWN_LISTING: You cannot start a conversation on your own crop listing' USING ERRCODE = 'P0001';
    END IF;

    -- Return existing conversation if already created
    SELECT id INTO v_conv_id
    FROM public.conversations
    WHERE listing_id = p_listing_id AND buyer_id = v_uid;

    IF v_conv_id IS NOT NULL THEN
        RETURN v_conv_id;
    END IF;

    -- Rate limiting: Max 20 new conversations per day
    SELECT COUNT(*) INTO v_created_24h
    FROM public.conversations
    WHERE buyer_id = v_uid
      AND created_at >= NOW() - INTERVAL '24 hours';

    IF v_created_24h >= 20 THEN
        RAISE EXCEPTION 'RATE_LIMITED: Daily conversation creation limit (20) reached. Please wait before contacting more sellers.' USING ERRCODE = 'P0004';
    END IF;

    -- Ensure profiles exist
    IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = v_uid) THEN
        INSERT INTO public.profiles (id, full_name, district_id, city_id)
        VALUES (v_uid, 'Buyer ' || SUBSTRING(v_uid::text, 1, 6), 1, 1)
        ON CONFLICT (id) DO NOTHING;
    END IF;

    -- Check if there is an active order for this listing & buyer to link immediately
    SELECT id INTO v_order_id
    FROM public.orders
    WHERE listing_id = p_listing_id
      AND buyer_id = v_uid
      AND status NOT IN ('rejected', 'cancelled', 'expired', 'completed', 'refunded')
    ORDER BY requested_at DESC
    LIMIT 1;

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

REVOKE ALL ON FUNCTION public.get_or_create_conversation(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_or_create_conversation(UUID) TO authenticated, service_role;

-- ============================================================================
-- 6. RPC: get_inbox(limit, cursor)
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
            ON (cr.conversation_id = uc.c_id OR (cr.order_id = uc.c_order_id AND cr.order_id IS NOT NULL))
            AND cr.user_id = v_uid
        LEFT JOIN public.messages m
            ON m.conversation_id = uc.c_id
            AND m.created_at > COALESCE(cr.last_read_at, '1970-01-01'::timestamptz)
            AND (m.sender_id IS NULL OR m.sender_id != v_uid)
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
-- 7. RPC: get_conversation_messages(conversation_id, cursor, limit)
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
        RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
    END IF;

    SELECT conversations.id, conversations.buyer_id, conversations.seller_id, conversations.order_id INTO v_conv
    FROM public.conversations
    WHERE conversations.id = p_conversation_id;

    IF NOT FOUND THEN
        SELECT conversations.id, conversations.buyer_id, conversations.seller_id, conversations.order_id INTO v_conv
        FROM public.conversations
        WHERE conversations.order_id = p_conversation_id;

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
-- 8. RPC: chat_send(conversation_id, sender_id, body, client_nonce)
-- ============================================================================
DROP FUNCTION IF EXISTS public.chat_send(uuid, uuid, text, uuid) CASCADE;
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
DECLARE
    v_conv RECORD;
    v_is_suspended BOOLEAN;
    v_recent_count INT;
    v_existing RECORD;
    v_new_message RECORD;
    v_recipient_id UUID;
    v_clean_body TEXT;
    v_crop_name TEXT;
    v_sender_name TEXT;
BEGIN
    IF p_conversation_id IS NULL OR p_sender_id IS NULL THEN
        RAISE EXCEPTION 'CONVERSATION_ID_AND_SENDER_REQUIRED' USING ERRCODE = 'P0001';
    END IF;

    v_clean_body := TRIM(p_body);
    IF LENGTH(v_clean_body) < 1 OR LENGTH(v_clean_body) > 1000 THEN
        RAISE EXCEPTION 'INVALID_BODY_LENGTH' USING ERRCODE = 'P0001';
    END IF;

    -- Lock conversation row
    SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
    INTO v_conv
    FROM public.conversations c
    WHERE c.id = p_conversation_id
    FOR UPDATE;

    IF NOT FOUND THEN
        SELECT c.id, c.listing_id, c.buyer_id, c.seller_id, c.order_id, c.status
        INTO v_conv
        FROM public.conversations c
        WHERE c.order_id = p_conversation_id
        FOR UPDATE;

        IF FOUND THEN
            p_conversation_id := v_conv.id;
        ELSE
            RAISE EXCEPTION 'CONVERSATION_NOT_FOUND' USING ERRCODE = 'P0001';
        END IF;
    END IF;

    -- Check sender is a party
    IF v_conv.buyer_id != p_sender_id AND v_conv.seller_id != p_sender_id THEN
        RAISE EXCEPTION 'NOT_A_PARTY' USING ERRCODE = 'P0001';
    END IF;

    -- Check conversation not blocked or closed
    IF v_conv.status IN ('closed', 'blocked') THEN
        RAISE EXCEPTION 'CONVERSATION_CLOSED' USING ERRCODE = 'P0002';
    END IF;

    -- Check sender not suspended
    SELECT is_suspended, full_name INTO v_is_suspended, v_sender_name
    FROM public.profiles
    WHERE id = p_sender_id;

    IF v_is_suspended IS TRUE THEN
        RAISE EXCEPTION 'SUSPENDED' USING ERRCODE = 'P0003';
    END IF;

    -- Max 20 messages per minute rate limit
    SELECT COUNT(*) INTO v_recent_count
    FROM public.messages
    WHERE sender_id = p_sender_id
      AND created_at >= NOW() - INTERVAL '1 minute';

    IF v_recent_count >= 20 THEN
        RAISE EXCEPTION 'RATE_LIMITED' USING ERRCODE = 'P0004';
    END IF;

    -- Idempotent retry: return existing row if nonce repeats
    IF p_client_nonce IS NOT NULL THEN
        SELECT id, conversation_id, order_id, sender_id, kind, body, client_nonce, created_at
        INTO v_existing
        FROM public.messages
        WHERE conversation_id = p_conversation_id
          AND sender_id = p_sender_id
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
        p_conversation_id,
        v_conv.order_id,
        p_sender_id,
        'user',
        v_clean_body,
        p_client_nonce,
        NOW()
    )
    RETURNING id, conversation_id, order_id, sender_id, kind, body, client_nonce, created_at
    INTO v_new_message;

    -- Update conversation last_message pointer
    UPDATE public.conversations
    SET last_message_id = v_new_message.id,
        last_message_at = v_new_message.created_at
    WHERE id = p_conversation_id;

    -- Update sender's read receipt
    INSERT INTO public.chat_reads (conversation_id, order_id, user_id, last_read_at)
    VALUES (p_conversation_id, v_conv.order_id, p_sender_id, NOW())
    ON CONFLICT (conversation_id, user_id)
    DO UPDATE SET last_read_at = NOW();

    -- Determine recipient
    v_recipient_id := CASE WHEN p_sender_id = v_conv.buyer_id THEN v_conv.seller_id ELSE v_conv.buyer_id END;

    SELECT crop_name INTO v_crop_name FROM public.listings WHERE id = v_conv.listing_id;

    -- Generic notification to counterpart (never the message text!)
    INSERT INTO public.notifications (
        user_id,
        type,
        title,
        body,
        order_id
    ) VALUES (
        v_recipient_id,
        'new_chat_message',
        'New Message',
        'New message regarding ' || COALESCE(v_crop_name, 'crop produce'),
        v_conv.order_id
    );

    RETURN jsonb_build_object(
        'id', v_new_message.id,
        'conversation_id', v_new_message.conversation_id,
        'order_id', v_new_message.order_id,
        'sender_id', v_new_message.sender_id,
        'kind', v_new_message.kind,
        'body', v_new_message.body,
        'client_nonce', v_new_message.client_nonce,
        'created_at', v_new_message.created_at,
        'is_duplicate', false
    );
END;
$$;

REVOKE ALL ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.chat_send(UUID, UUID, TEXT, UUID) TO service_role, postgres;

-- ============================================================================
-- 9. RPC: mark_conversation_read(conversation_id)
-- ============================================================================
DROP FUNCTION IF EXISTS public.mark_conversation_read(UUID) CASCADE;
CREATE OR REPLACE FUNCTION public.mark_conversation_read(p_conversation_id UUID)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_uid UUID := auth.uid();
BEGIN
    IF v_uid IS NULL THEN
        RETURN;
    END IF;

    -- Resolve conversation if order_id was provided
    IF NOT EXISTS (SELECT 1 FROM public.conversations WHERE id = p_conversation_id) THEN
        SELECT id INTO p_conversation_id FROM public.conversations WHERE order_id = p_conversation_id;
        IF p_conversation_id IS NULL THEN
            RETURN;
        END IF;
    END IF;

    INSERT INTO public.chat_reads (conversation_id, user_id, last_read_at)
    VALUES (p_conversation_id, v_uid, NOW())
    ON CONFLICT (conversation_id, user_id)
    DO UPDATE SET last_read_at = NOW();
END;
$$;

REVOKE ALL ON FUNCTION public.mark_conversation_read(UUID) FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.mark_conversation_read(UUID) TO authenticated, service_role;

-- Overloaded record_blocked_message supporting conversation_id
DROP FUNCTION IF EXISTS public.record_blocked_message(UUID, UUID, UUID, TEXT, TEXT[]) CASCADE;
DROP FUNCTION IF EXISTS public.record_blocked_message(UUID, UUID, TEXT, TEXT[]) CASCADE;
CREATE OR REPLACE FUNCTION public.record_blocked_message(
    p_conversation_id UUID,
    p_order_id UUID,
    p_sender_id UUID,
    p_original_body TEXT,
    p_reasons TEXT[]
)
RETURNS VOID
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, auth, pg_temp
AS $$
DECLARE
    v_24h_count INT;
    v_order_num TEXT;
BEGIN
    INSERT INTO public.flagged_messages (
        conversation_id,
        order_id,
        sender_id,
        original_body,
        reasons,
        reviewed,
        created_at
    ) VALUES (
        p_conversation_id,
        p_order_id,
        p_sender_id,
        p_original_body,
        p_reasons,
        false,
        NOW()
    );

    SELECT COUNT(*) INTO v_24h_count
    FROM public.flagged_messages
    WHERE sender_id = p_sender_id
      AND created_at >= NOW() - INTERVAL '24 hours';

    IF v_24h_count >= 3 THEN
        IF p_order_id IS NOT NULL THEN
            SELECT order_number INTO v_order_num FROM public.orders WHERE id = p_order_id;
        END IF;

        IF NOT EXISTS (
            SELECT 1 FROM public.admin_alerts
            WHERE user_id = p_sender_id
              AND alert_type = 'chat_abuse'
              AND created_at >= NOW() - INTERVAL '24 hours'
        ) THEN
            INSERT INTO public.admin_alerts (
                alert_type,
                severity,
                title,
                description,
                user_id,
                order_id,
                created_at
            ) VALUES (
                'chat_abuse',
                'high',
                'Repeated Off-Platform Contact Leaks',
                'User attempted to send prohibited contact details or external chat links 3+ times in the last 24 hours.',
                p_sender_id,
                p_order_id,
                NOW()
            );
        END IF;
    END IF;
END;
$$;

REVOKE ALL ON FUNCTION public.record_blocked_message(UUID, UUID, UUID, TEXT, TEXT[]) FROM PUBLIC, anon, authenticated;
GRANT EXECUTE ON FUNCTION public.record_blocked_message(UUID, UUID, UUID, TEXT, TEXT[]) TO service_role, postgres;
GRANT EXECUTE ON FUNCTION public.mark_conversation_read(UUID) TO authenticated, service_role;

-- ============================================================================
-- 10. TRIGGERS: LINK ORDER TO CONVERSATION & POST STATUS SYSTEM MESSAGES
-- ============================================================================

-- Trigger on Orders Insert: Link conversation to newly placed real order & post system notice
DROP TRIGGER IF EXISTS trg_order_link_conv ON orders;
DROP FUNCTION IF EXISTS trigger_order_link_conversation() CASCADE;
CREATE OR REPLACE FUNCTION trigger_order_link_conversation()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_conv_id UUID;
BEGIN
    -- Check if conversation already exists for (listing_id, buyer_id)
    SELECT id INTO v_conv_id
    FROM public.conversations
    WHERE listing_id = NEW.listing_id AND buyer_id = NEW.buyer_id
    LIMIT 1;

    IF v_conv_id IS NOT NULL THEN
        UPDATE public.conversations
        SET order_id = NEW.id
        WHERE id = v_conv_id;
    ELSE
        -- Auto-create conversation for this order if none existed
        INSERT INTO public.conversations (
            listing_id,
            buyer_id,
            seller_id,
            order_id,
            status,
            created_at,
            last_message_at
        ) VALUES (
            NEW.listing_id,
            NEW.buyer_id,
            NEW.farmer_id,
            NEW.id,
            'active',
            NOW(),
            NOW()
        )
        RETURNING id INTO v_conv_id;
    END IF;

    -- Post initial order placed system message
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
        NEW.id,
        NULL,
        'system',
        'Order #' || NEW.order_number || ' requested for ' || NEW.quantity_kg || ' kg of ' || NEW.crop_name || ' (Rs. ' || NEW.subtotal || '). Awaiting farmer confirmation.',
        gen_random_uuid(),
        NOW()
    );

    UPDATE public.conversations
    SET last_message_at = NOW()
    WHERE id = v_conv_id;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_order_link_conv ON orders;
CREATE TRIGGER trg_order_link_conv
AFTER INSERT ON orders
FOR EACH ROW
EXECUTE FUNCTION trigger_order_link_conversation();

-- Trigger on Orders Update: Post system message to linked conversation on status changes
DROP TRIGGER IF EXISTS trg_order_status_system_message ON orders;
DROP FUNCTION IF EXISTS trigger_order_status_system_message() CASCADE;
CREATE OR REPLACE FUNCTION trigger_order_status_system_message()
RETURNS TRIGGER
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_conv_id UUID;
    v_msg TEXT;
BEGIN
    IF (TG_OP = 'UPDATE' AND OLD.status <> NEW.status) THEN
        -- Find linked conversation
        SELECT id INTO v_conv_id
        FROM public.conversations
        WHERE order_id = NEW.id
        LIMIT 1;

        IF v_conv_id IS NULL THEN
            SELECT id INTO v_conv_id
            FROM public.conversations
            WHERE listing_id = NEW.listing_id AND buyer_id = NEW.buyer_id
            LIMIT 1;

            IF v_conv_id IS NOT NULL THEN
                UPDATE public.conversations SET order_id = NEW.id WHERE id = v_conv_id;
            END IF;
        END IF;

        IF v_conv_id IS NOT NULL THEN
            v_msg := CASE NEW.status
                WHEN 'accepted' THEN 'Order #' || NEW.order_number || ' accepted by farmer. Awaiting buyer payment.'
                WHEN 'rejected' THEN 'Order #' || NEW.order_number || ' was declined by farmer.'
                WHEN 'paid' THEN 'Payment confirmed for Order #' || NEW.order_number || '. Farmer is preparing produce.'
                WHEN 'ready' THEN 'Order #' || NEW.order_number || ' is packed and ready for dispatch/pickup.'
                WHEN 'dispatched' THEN 'Order #' || NEW.order_number || ' is out for delivery.'
                WHEN 'delivered' THEN 'Order #' || NEW.order_number || ' delivered. Please confirm receipt in the app.'
                WHEN 'completed' THEN 'Order #' || NEW.order_number || ' marked completed. Deal finalized!'
                WHEN 'cancelled' THEN 'Order #' || NEW.order_number || ' was cancelled.'
                WHEN 'expired' THEN 'Order #' || NEW.order_number || ' acceptance window expired.'
                WHEN 'disputed' THEN 'Dispute opened for Order #' || NEW.order_number || '. AgroMarket Admin is reviewing.'
                WHEN 'refunded' THEN 'Refund processed for Order #' || NEW.order_number || '.'
                ELSE NULL
            END;

            IF v_msg IS NOT NULL THEN
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
                    NEW.id,
                    NULL,
                    'system',
                    v_msg,
                    gen_random_uuid(),
                    NOW()
                );

                UPDATE public.conversations
                SET last_message_at = NOW()
                WHERE id = v_conv_id;
            END IF;
        END IF;
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_order_status_system_message ON orders;
CREATE TRIGGER trg_order_status_system_message
AFTER UPDATE ON orders
FOR EACH ROW
EXECUTE FUNCTION trigger_order_status_system_message();

-- Enable Supabase Realtime for conversations
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_publication_tables
        WHERE pubname = 'supabase_realtime' AND tablename = 'conversations'
    ) THEN
        ALTER PUBLICATION supabase_realtime ADD TABLE public.conversations;
    END IF;
END $$;

COMMIT;

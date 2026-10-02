-- AgroMarket Migration 17: Single Conversation Per Buyer-Farmer Pair
-- Ensures 1 farmer and 1 buyer have exactly 1 continuous conversation thread across all listings.

-- 1. Ensure any missing optional columns exist safely
ALTER TABLE public.profiles ADD COLUMN IF NOT EXISTS avatar_url TEXT;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ DEFAULT NOW();
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS last_message_preview TEXT;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS last_message_sender_id UUID;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS deleted_by_buyer BOOLEAN DEFAULT FALSE;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS deleted_by_seller BOOLEAN DEFAULT FALSE;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS buyer_deleted_at TIMESTAMPTZ;
ALTER TABLE public.conversations ADD COLUMN IF NOT EXISTS seller_deleted_at TIMESTAMPTZ;

-- 2. Re-point any messages from older duplicate conversations to the latest conversation
WITH duplicates AS (
    SELECT id, buyer_id, seller_id,
           ROW_NUMBER() OVER (
               PARTITION BY LEAST(buyer_id, seller_id), GREATEST(buyer_id, seller_id) 
               ORDER BY last_message_at DESC, created_at DESC
           ) as rn,
           FIRST_VALUE(id) OVER (
               PARTITION BY LEAST(buyer_id, seller_id), GREATEST(buyer_id, seller_id) 
               ORDER BY last_message_at DESC, created_at DESC
           ) as keep_id
    FROM public.conversations
)
UPDATE public.messages m
SET conversation_id = d.keep_id
FROM duplicates d
WHERE m.conversation_id = d.id AND d.rn > 1;

-- 3. Delete older duplicate conversation records
WITH duplicates AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY LEAST(buyer_id, seller_id), GREATEST(buyer_id, seller_id) 
               ORDER BY last_message_at DESC, created_at DESC
           ) as rn
    FROM public.conversations
)
DELETE FROM public.conversations
WHERE id IN (SELECT id FROM duplicates WHERE rn > 1);

-- 4. Update constraints: drop the per-listing constraint and enforce unique conversation per buyer-farmer pair
ALTER TABLE public.conversations DROP CONSTRAINT IF EXISTS uq_conversations_listing_buyer;
ALTER TABLE public.conversations ALTER COLUMN listing_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_conversations_buyer_farmer_pair
ON public.conversations (LEAST(buyer_id, seller_id), GREATEST(buyer_id, seller_id));

-- 5. Update get_or_create_conversation to return the single buyer-farmer conversation
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
    v_buyer_id UUID;
    v_seller_id UUID;
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

    -- Determine buyer and seller
    IF v_listing.farmer_id = v_uid THEN
        -- Current user is the farmer; find existing conversation with any buyer or return latest
        SELECT id INTO v_conv_id
        FROM public.conversations
        WHERE seller_id = v_uid OR buyer_id = v_uid
        ORDER BY last_message_at DESC
        LIMIT 1;

        IF v_conv_id IS NOT NULL THEN
            RETURN v_conv_id;
        END IF;

        v_buyer_id := '00000000-0000-0000-0000-000000000001'::UUID;
        v_seller_id := v_uid;
    ELSE
        v_buyer_id := v_uid;
        v_seller_id := v_listing.farmer_id;
    END IF;

    -- 1. Check if a conversation ALREADY exists between this buyer and this farmer (across any listing)
    SELECT id INTO v_conv_id
    FROM public.conversations
    WHERE (buyer_id = v_buyer_id AND seller_id = v_seller_id)
       OR (buyer_id = v_seller_id AND seller_id = v_buyer_id)
    ORDER BY last_message_at DESC
    LIMIT 1;

    IF v_conv_id IS NOT NULL THEN
        -- Update the conversation's active listing_id to the one currently being viewed/discussed
        UPDATE public.conversations
        SET listing_id = p_listing_id,
            last_message_at = NOW(),
            updated_at = NOW()
        WHERE id = v_conv_id;
        RETURN v_conv_id;
    END IF;

    -- 2. Link active order if one exists between them
    SELECT id INTO v_order_id
    FROM public.orders
    WHERE buyer_id = v_buyer_id AND farmer_id = v_seller_id
      AND status NOT IN ('rejected', 'cancelled', 'expired', 'completed', 'refunded')
    ORDER BY requested_at DESC
    LIMIT 1;

    -- 3. Ensure profiles exist
    IF NOT EXISTS (SELECT 1 FROM public.profiles WHERE id = v_buyer_id) THEN
        INSERT INTO public.profiles (id, full_name, district_id, city_id)
        VALUES (v_buyer_id, 'Buyer ' || SUBSTRING(v_buyer_id::text, 1, 6), 1, 1)
        ON CONFLICT (id) DO NOTHING;
    END IF;

    -- 4. Create new single conversation between buyer and farmer
    INSERT INTO public.conversations (
        listing_id,
        buyer_id,
        seller_id,
        order_id,
        status,
        created_at,
        last_message_at,
        updated_at
    ) VALUES (
        p_listing_id,
        v_buyer_id,
        v_seller_id,
        v_order_id,
        'active',
        NOW(),
        NOW(),
        NOW()
    )
    ON CONFLICT (LEAST(buyer_id, seller_id), GREATEST(buyer_id, seller_id))
    DO UPDATE SET listing_id = EXCLUDED.listing_id, last_message_at = NOW(), updated_at = NOW()
    RETURNING id INTO v_conv_id;

    -- 5. Add initial welcoming system message
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

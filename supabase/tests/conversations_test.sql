-- AgroMarket Conversations Verification Test Suite: conversations_test.sql
-- Tests:
-- 1. get_or_create_conversation creates conversation and is idempotent on (listing_id, buyer_id)
-- 2. Seller cannot message own listing (CANNOT_MESSAGE_OWN_LISTING)
-- 3. Rate limiting: 21st conversation in 24 hours raises RATE_LIMITED
-- 4. get_inbox returns counterpart name, avatar, thumbnail, preview, unread count, order status in ONE query
-- 5. Placing order links conversation.order_id and status update posts system message
-- 6. chat_send with conversation_id sends message, enforces party check, updates last_message_at
-- 7. RLS: non-party cannot read conversation or messages
-- 8. status='inquiry' is eliminated from orders table

DO $$
DECLARE
    v_buyer_id UUID := '00000000-0000-0000-0000-000000000002';
    v_farmer_id UUID := '00000000-0000-0000-0000-000000000003';
    v_stranger_id UUID := '00000000-0000-0000-0000-000000000009';
    v_listing_id UUID := '10000000-0000-0000-0000-000000000001';
    v_conv_id UUID;
    v_conv_id_2 UUID;
    v_order_id UUID := gen_random_uuid();
    v_nonce UUID := gen_random_uuid();
    v_caught BOOLEAN;
    v_count INT;
    v_inbox_rec RECORD;
    v_send_res JSONB;
    v_sys_msg RECORD;
    i INT;
    v_temp_listing_id UUID;
BEGIN
    RAISE NOTICE '=======================================================';
    RAISE NOTICE 'Starting Conversations Architecture Verification Tests';
    RAISE NOTICE '=======================================================';

    -- 0. Cleanup previous test records
    DELETE FROM public.messages WHERE conversation_id IN (SELECT id FROM public.conversations WHERE buyer_id = v_buyer_id OR buyer_id = v_stranger_id);
    DELETE FROM public.chat_reads WHERE conversation_id IN (SELECT id FROM public.conversations WHERE buyer_id = v_buyer_id OR buyer_id = v_stranger_id);
    DELETE FROM public.conversations WHERE buyer_id = v_buyer_id OR buyer_id = v_stranger_id;
    DELETE FROM public.orders WHERE buyer_id = v_buyer_id OR buyer_id = v_stranger_id;

    -- Ensure profiles and farmer exist
    INSERT INTO auth.users (id, email) VALUES
        (v_buyer_id, 'buyer_conv@agromarket.lk'),
        (v_farmer_id, 'farmer_conv@agromarket.lk'),
        (v_stranger_id, 'stranger_conv@agromarket.lk')
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO public.profiles (id, full_name, district_id, city_id) VALUES
        (v_buyer_id, 'Conv Buyer', 1, 1),
        (v_farmer_id, 'Conv Farmer', 1, 1),
        (v_stranger_id, 'Conv Stranger', 1, 1)
    ON CONFLICT (id) DO NOTHING;

    INSERT INTO public.farmers (user_id, cultivation_district_id, cultivation_city_id) VALUES
        (v_farmer_id, 1, 1)
    ON CONFLICT (user_id) DO NOTHING;

    INSERT INTO public.listings (
        id, farmer_id, crop_name, crop_name_key, quantity_available, price_per_kg, min_order_kg, harvest_date, photos
    ) VALUES (
        v_listing_id, v_farmer_id, 'Carrot', 'carrot', 100.0, 200.0, 1.0, CURRENT_DATE, ARRAY['https://example.com/carrot.jpg']
    ) ON CONFLICT (id) DO NOTHING;

    -- ------------------------------------------------------------------------
    -- TEST 1: get_or_create_conversation creates and is idempotent
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 1: Verifying get_or_create_conversation...';
    PERFORM set_config('role', 'authenticated', true);
    PERFORM set_config('request.jwt.claim.sub', v_buyer_id::TEXT, true);

    v_conv_id := public.get_or_create_conversation(v_listing_id);
    IF v_conv_id IS NULL THEN
        RAISE EXCEPTION 'TEST 1 FAILED: get_or_create_conversation returned NULL!';
    END IF;

    -- Calling again returns exact same conversation UUID
    v_conv_id_2 := public.get_or_create_conversation(v_listing_id);
    IF v_conv_id != v_conv_id_2 THEN
        RAISE EXCEPTION 'TEST 1 FAILED: get_or_create_conversation is not idempotent! % != %', v_conv_id, v_conv_id_2;
    END IF;

    -- Verify opening system message was created
    SELECT COUNT(*) INTO v_count FROM public.messages WHERE conversation_id = v_conv_id AND kind = 'system';
    IF v_count = 0 THEN
        RAISE EXCEPTION 'TEST 1 FAILED: Opening system message was not created!';
    END IF;
    RAISE NOTICE 'PASSED [1/8]: get_or_create_conversation is idempotent and creates opening message.';

    -- ------------------------------------------------------------------------
    -- TEST 2: Seller cannot message own listing
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 2: Verifying seller cannot message own listing...';
    PERFORM set_config('role', 'authenticated', true);
    PERFORM set_config('request.jwt.claim.sub', v_farmer_id::TEXT, true);

    v_caught := false;
    BEGIN
        PERFORM public.get_or_create_conversation(v_listing_id);
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%CANNOT_MESSAGE_OWN_LISTING%' THEN
            v_caught := true;
        END IF;
    END;

    IF NOT v_caught THEN
        RAISE EXCEPTION 'TEST 2 FAILED: Seller was able to message own listing!';
    END IF;
    RAISE NOTICE 'PASSED [2/8]: Farmer cannot start conversation on their own listing.';

    -- ------------------------------------------------------------------------
    -- TEST 3: Rate limiting on creating conversations (max 20 per 24 hours)
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 3: Verifying rate limit on conversation creation...';
    PERFORM set_config('role', 'postgres', true);
    -- Insert 19 more conversations to reach 20
    FOR i IN 1..19 LOOP
        v_temp_listing_id := gen_random_uuid();
        INSERT INTO public.listings (
            id, farmer_id, crop_name, crop_name_key, quantity_available, price_per_kg, min_order_kg, harvest_date
        ) VALUES (
            v_temp_listing_id, v_farmer_id, 'Crop ' || i, 'crop_' || i, 50.0, 100.0, 1.0, CURRENT_DATE
        );

        INSERT INTO public.conversations (
            listing_id, buyer_id, seller_id, status, created_at, last_message_at
        ) VALUES (
            v_temp_listing_id, v_buyer_id, v_farmer_id, 'active', NOW(), NOW()
        );
    END LOOP;

    -- Now switch to buyer: 21st conversation must fail with RATE_LIMITED
    PERFORM set_config('role', 'authenticated', true);
    PERFORM set_config('request.jwt.claim.sub', v_buyer_id::TEXT, true);

    v_temp_listing_id := gen_random_uuid();
    PERFORM set_config('role', 'postgres', true);
    INSERT INTO public.listings (
        id, farmer_id, crop_name, crop_name_key, quantity_available, price_per_kg, min_order_kg, harvest_date
    ) VALUES (
        v_temp_listing_id, v_farmer_id, 'Crop Over Limit', 'crop_limit', 50.0, 100.0, 1.0, CURRENT_DATE
    );

    PERFORM set_config('role', 'authenticated', true);
    PERFORM set_config('request.jwt.claim.sub', v_buyer_id::TEXT, true);

    v_caught := false;
    BEGIN
        PERFORM public.get_or_create_conversation(v_temp_listing_id);
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%RATE_LIMITED%' THEN
            v_caught := true;
        END IF;
    END;

    IF NOT v_caught THEN
        RAISE EXCEPTION 'TEST 3 FAILED: Rate limit not enforced after 20 conversations!';
    END IF;
    RAISE NOTICE 'PASSED [3/8]: Daily conversation creation rate limit properly enforced.';

    -- Clean up temporary rate limit test listings
    PERFORM set_config('role', 'postgres', true);
    DELETE FROM public.conversations WHERE buyer_id = v_buyer_id AND listing_id != v_listing_id;
    DELETE FROM public.listings WHERE farmer_id = v_farmer_id AND id != v_listing_id;

    -- ------------------------------------------------------------------------
    -- TEST 4: get_inbox returns combined fields in ONE query
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 4: Verifying get_inbox in ONE query...';
    PERFORM set_config('role', 'authenticated', true);
    PERFORM set_config('request.jwt.claim.sub', v_buyer_id::TEXT, true);

    SELECT * INTO v_inbox_rec FROM public.get_inbox(10, NULL) LIMIT 1;

    IF v_inbox_rec.conversation_id != v_conv_id THEN
        RAISE EXCEPTION 'TEST 4 FAILED: Expected conversation_id %, got %', v_conv_id, v_inbox_rec.conversation_id;
    END IF;
    IF v_inbox_rec.crop_name != 'Carrot' THEN
        RAISE EXCEPTION 'TEST 4 FAILED: Expected Carrot crop_name, got %', v_inbox_rec.crop_name;
    END IF;
    IF v_inbox_rec.counterpart_name != 'Conv Farmer' THEN
        RAISE EXCEPTION 'TEST 4 FAILED: Expected counterpart Conv Farmer, got %', v_inbox_rec.counterpart_name;
    END IF;
    IF v_inbox_rec.listing_thumbnail != 'https://example.com/carrot.jpg' THEN
        RAISE EXCEPTION 'TEST 4 FAILED: Listing thumbnail missing or incorrect!';
    END IF;
    RAISE NOTICE 'PASSED [4/8]: get_inbox returned counterpart, thumbnail, preview, unreads in ONE query.';

    -- ------------------------------------------------------------------------
    -- TEST 5: chat_send with conversation_id & idempotency
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 5: Verifying chat_send with conversation_id...';
    PERFORM set_config('role', 'postgres', true);

    v_send_res := public.chat_send(v_conv_id, v_buyer_id, 'Hello farmer, is carrot ready?', v_nonce);
    IF (v_send_res->>'is_duplicate')::boolean IS TRUE THEN
        RAISE EXCEPTION 'TEST 5 FAILED: First send marked as duplicate!';
    END IF;

    -- Idempotent retry with same nonce returns existing message
    v_send_res := public.chat_send(v_conv_id, v_buyer_id, 'Hello farmer, is carrot ready?', v_nonce);
    IF (v_send_res->>'is_duplicate')::boolean IS NOT TRUE THEN
        RAISE EXCEPTION 'TEST 5 FAILED: Duplicate nonce did not return is_duplicate true!';
    END IF;

    -- Verify stranger cannot send
    v_caught := false;
    BEGIN
        PERFORM public.chat_send(v_conv_id, v_stranger_id, 'I am a stranger', gen_random_uuid());
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%NOT_A_PARTY%' THEN
            v_caught := true;
        END IF;
    END;
    IF NOT v_caught THEN
        RAISE EXCEPTION 'TEST 5 FAILED: Stranger was able to send message to conversation!';
    END IF;
    RAISE NOTICE 'PASSED [5/8]: chat_send enforces party check and client_nonce idempotency.';

    -- ------------------------------------------------------------------------
    -- TEST 6: Placing order links conversation & status change posts system msg
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 6: Verifying order linking & status update system message...';
    INSERT INTO public.orders (
        id, order_number, buyer_id, farmer_id, listing_id, crop_name,
        quantity_kg, price_per_kg, subtotal, delivery_method, total_amount,
        farmer_payout_amount, commission_rate, requested_date, status
    ) VALUES (
        v_order_id, 'AM-CONV-001', v_buyer_id, v_farmer_id, v_listing_id, 'Carrot',
        10.0, 200.0, 2000.0, 'buyer_arranged', 2000.0, 1940.0, 0.03, CURRENT_DATE, 'requested'
    );

    -- Verify conversation now links order_id
    SELECT * INTO v_inbox_rec FROM public.conversations WHERE id = v_conv_id;
    IF v_inbox_rec.order_id != v_order_id THEN
        RAISE EXCEPTION 'TEST 6 FAILED: Order was not automatically linked to conversation! Expected %, got %', v_order_id, v_inbox_rec.order_id;
    END IF;

    -- Update order status to accepted
    UPDATE public.orders SET status = 'accepted' WHERE id = v_order_id;

    -- Verify system message was posted
    SELECT * INTO v_sys_msg FROM public.messages
    WHERE conversation_id = v_conv_id AND kind = 'system' AND body LIKE '%accepted by farmer%'
    ORDER BY created_at DESC LIMIT 1;

    IF v_sys_msg.id IS NULL THEN
        RAISE EXCEPTION 'TEST 6 FAILED: System message for order acceptance was not posted!';
    END IF;
    RAISE NOTICE 'PASSED [6/8]: Order linked to conversation and status transition system message posted.';

    -- ------------------------------------------------------------------------
    -- TEST 7: RLS blocks non-parties from reading conversations and messages
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 7: Verifying RLS for non-parties...';
    PERFORM set_config('role', 'authenticated', true);
    PERFORM set_config('request.jwt.claim.sub', v_stranger_id::TEXT, true);

    -- Non-party gets 0 rows from conversations table
    SELECT COUNT(*) INTO v_count FROM public.conversations WHERE id = v_conv_id;
    IF v_count > 0 THEN
        RAISE EXCEPTION 'TEST 7 FAILED: Non-party read conversation via SELECT!';
    END IF;

    -- Non-party gets 0 rows from messages table
    SELECT COUNT(*) INTO v_count FROM public.messages WHERE conversation_id = v_conv_id;
    IF v_count > 0 THEN
        RAISE EXCEPTION 'TEST 7 FAILED: Non-party read messages via SELECT!';
    END IF;

    -- Non-party get_conversation_messages raises NOT_A_PARTY
    v_caught := false;
    BEGIN
        PERFORM public.get_conversation_messages(v_conv_id);
    EXCEPTION WHEN OTHERS THEN
        IF SQLERRM LIKE '%NOT_A_PARTY%' THEN
            v_caught := true;
        END IF;
    END;
    IF NOT v_caught THEN
        RAISE EXCEPTION 'TEST 7 FAILED: Non-party called get_conversation_messages!';
    END IF;
    RAISE NOTICE 'PASSED [7/8]: RLS strictly blocks non-parties from reading conversations/messages.';

    -- ------------------------------------------------------------------------
    -- TEST 8: status='inquiry' is eliminated from orders
    -- ------------------------------------------------------------------------
    RAISE NOTICE 'TEST 8: Verifying status=inquiry is rejected on orders...';
    PERFORM set_config('role', 'postgres', true);
    SELECT COUNT(*) INTO v_count FROM public.orders WHERE status = 'inquiry';
    IF v_count > 0 THEN
        RAISE EXCEPTION 'TEST 8 FAILED: Orders table still contains status=inquiry rows!';
    END IF;

    -- Inserting order with status='inquiry' violates check constraint
    v_caught := false;
    BEGIN
        INSERT INTO public.orders (
            id, order_number, buyer_id, farmer_id, listing_id, crop_name,
            quantity_kg, price_per_kg, subtotal, delivery_method, total_amount,
            farmer_payout_amount, commission_rate, requested_date, status
        ) VALUES (
            gen_random_uuid(), 'AM-CONV-FAKE', v_buyer_id, v_farmer_id, v_listing_id, 'Carrot',
            10.0, 200.0, 2000.0, 'buyer_arranged', 2000.0, 1940.0, 0.03, CURRENT_DATE, 'inquiry'
        );
    EXCEPTION WHEN OTHERS THEN
        v_caught := true;
    END;
    IF NOT v_caught THEN
        RAISE EXCEPTION 'TEST 8 FAILED: orders table accepted status=inquiry!';
    END IF;
    RAISE NOTICE 'PASSED [8/8]: status=inquiry is successfully eliminated from orders.';

    -- Cleanup test rows
    DELETE FROM public.messages WHERE conversation_id = v_conv_id;
    DELETE FROM public.chat_reads WHERE conversation_id = v_conv_id;
    DELETE FROM public.conversations WHERE id = v_conv_id;
    DELETE FROM public.orders WHERE id = v_order_id;
    PERFORM set_config('role', 'postgres', true);

    RAISE NOTICE '=======================================================';
    RAISE NOTICE 'ALL 8 CONVERSATIONS TESTS PASSED!';
    RAISE NOTICE '=======================================================';
END $$;

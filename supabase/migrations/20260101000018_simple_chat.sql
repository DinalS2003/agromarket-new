-- =====================================================================
-- AgroMarket: SIMPLE CHAT (text only)
-- One conversation per (buyer, seller) pair. Messages are plain text.
-- Removes: offers, images, receipts/ticks, typing, block/report,
--          delete/restore conversation, moderation, outbox, chat_reads.
-- Keeps:   order-status system messages (they show as centered lines).
-- Run in the Supabase SQL editor. It is one transaction: if anything
-- fails, nothing is applied.
-- =====================================================================
BEGIN;

-- ---------------------------------------------------------------------
-- 1. Drop every old chat function (all overloads) + triggers that use them
-- ---------------------------------------------------------------------
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT p.oid::regprocedure AS sig
    FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
    WHERE n.nspname = 'public'
      AND p.proname = ANY (ARRAY[
        'get_inbox','delete_conversation','restore_conversation',
        'mark_conversation_read','mark_messages_delivered',
        'get_conversation_receipts','get_messages_since','get_order_messages',
        'trigger_order_link_conversation','trigger_order_status_system_message',
        'get_or_create_conversation','get_conversation_messages',
        'create_chat_offer','respond_to_chat_offer','create_or_get_inquiry_chat',
        'mark_chat_read','admin_get_dispute_chat','get_chat_overview','chat_send',
        'block_user','unblock_user','report_user','is_user_blocked','set_typing'
      ])
  LOOP
    EXECUTE 'DROP FUNCTION ' || r.sig || ' CASCADE';
  END LOOP;
END $$;

-- any remaining triggers on the two chat tables
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT t.tgname, c.relname
    FROM pg_trigger t
    JOIN pg_class c ON c.oid = t.tgrelid
    JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relname IN ('messages','conversations')
      AND NOT t.tgisinternal
  LOOP
    EXECUTE format('DROP TRIGGER %I ON public.%I', r.tgname, r.relname);
  END LOOP;
END $$;

-- all old policies on the two chat tables
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT policyname, tablename FROM pg_policies
    WHERE schemaname = 'public' AND tablename IN ('messages','conversations')
  LOOP
    EXECUTE format('DROP POLICY %I ON public.%I', r.policyname, r.tablename);
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- 2. Drop extra-feature tables
--    (flagged_messages is kept so the admin page keeps loading; it will
--     simply stop receiving rows.)
-- ---------------------------------------------------------------------
DROP TABLE IF EXISTS public.chat_reads    CASCADE;
DROP TABLE IF EXISTS public.user_blocks   CASCADE;
DROP TABLE IF EXISTS public.user_reports  CASCADE;

-- ---------------------------------------------------------------------
-- 3. conversations: slim down
-- ---------------------------------------------------------------------
ALTER TABLE public.conversations
  DROP COLUMN IF EXISTS order_id,
  DROP COLUMN IF EXISTS status,
  DROP COLUMN IF EXISTS last_message_id,
  DROP COLUMN IF EXISTS deleted_by_buyer,
  DROP COLUMN IF EXISTS deleted_by_seller,
  DROP COLUMN IF EXISTS buyer_deleted_at,
  DROP COLUMN IF EXISTS seller_deleted_at,
  DROP COLUMN IF EXISTS updated_at;

-- exactly one conversation per buyer/seller pair
DROP INDEX IF EXISTS public.uq_conversations_buyer_farmer_pair;
DROP INDEX IF EXISTS public.uq_conversations_pair;
CREATE UNIQUE INDEX uq_conversations_pair
  ON public.conversations (buyer_id, seller_id);

CREATE INDEX IF NOT EXISTS idx_conversations_buyer_last
  ON public.conversations (buyer_id, last_message_at DESC);
CREATE INDEX IF NOT EXISTS idx_conversations_seller_last
  ON public.conversations (seller_id, last_message_at DESC);

-- ---------------------------------------------------------------------
-- 4. messages: slim down
-- ---------------------------------------------------------------------
-- storage policies (e.g. "Chat participants can view attachments") reference
-- messages.attachment_path and block the column drop -> remove them first
DO $$
DECLARE r record;
BEGIN
  FOR r IN
    SELECT policyname FROM pg_policies
    WHERE schemaname = 'storage' AND tablename = 'objects'
      AND (coalesce(qual,'') ILIKE '%attachment_path%'
        OR coalesce(with_check,'') ILIKE '%attachment_path%'
        OR coalesce(qual,'') ILIKE '%public.messages%'
        OR coalesce(with_check,'') ILIKE '%public.messages%'
        OR policyname ILIKE '%chat%attachment%')
  LOOP
    EXECUTE format('DROP POLICY %I ON storage.objects', r.policyname);
  END LOOP;
END $$;

-- old system rows were marked by kind='system'; new rule: sender_id IS NULL = system
UPDATE public.messages SET sender_id = NULL WHERE kind = 'system';

ALTER TABLE public.messages
  DROP COLUMN IF EXISTS kind,
  DROP COLUMN IF EXISTS client_nonce,
  DROP COLUMN IF EXISTS attachment_path,
  DROP COLUMN IF EXISTS attachment_url,
  DROP COLUMN IF EXISTS offer_price,
  DROP COLUMN IF EXISTS offer_quantity,
  DROP COLUMN IF EXISTS offer_date,
  DROP COLUMN IF EXISTS offer_status,
  DROP COLUMN IF EXISTS offer_order_id;

ALTER TABLE public.messages ADD COLUMN IF NOT EXISTS read_at timestamptz;

-- make sure every order pair has a conversation, then attach old messages
INSERT INTO public.conversations (buyer_id, seller_id, listing_id)
SELECT DISTINCT ON (o.buyer_id, o.farmer_id) o.buyer_id, o.farmer_id, o.listing_id
FROM public.orders o
WHERE NOT EXISTS (
  SELECT 1 FROM public.conversations c
  WHERE c.buyer_id = o.buyer_id AND c.seller_id = o.farmer_id)
ORDER BY o.buyer_id, o.farmer_id, o.created_at DESC;

UPDATE public.messages m
SET conversation_id = c.id
FROM public.orders o, public.conversations c
WHERE m.conversation_id IS NULL
  AND m.order_id = o.id
  AND c.buyer_id = o.buyer_id AND c.seller_id = o.farmer_id;

DELETE FROM public.messages WHERE conversation_id IS NULL;   -- unreachable leftovers
ALTER TABLE public.messages ALTER COLUMN conversation_id SET NOT NULL;

-- old messages count as read
UPDATE public.messages SET read_at = created_at WHERE read_at IS NULL;

-- rebuild last-message info
UPDATE public.conversations c
SET last_message_at        = m.created_at,
    last_message_preview   = left(m.body, 80),
    last_message_sender_id = m.sender_id
FROM (
  SELECT DISTINCT ON (conversation_id) conversation_id, created_at, body, sender_id
  FROM public.messages
  ORDER BY conversation_id, created_at DESC, id DESC
) m
WHERE m.conversation_id = c.id;

UPDATE public.conversations SET last_message_preview = NULL
WHERE NOT EXISTS (SELECT 1 FROM public.messages x WHERE x.conversation_id = conversations.id);

DROP INDEX IF EXISTS public.idx_messages_order_created;
CREATE INDEX IF NOT EXISTS idx_messages_conv_created
  ON public.messages (conversation_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_messages_unread
  ON public.messages (conversation_id) WHERE read_at IS NULL;

-- ---------------------------------------------------------------------
-- 5. Helper: is the caller an admin?
-- ---------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.chat_is_admin()
RETURNS boolean
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$ SELECT EXISTS (SELECT 1 FROM public.admin_users WHERE user_id = auth.uid()); $$;

-- ---------------------------------------------------------------------
-- 6. Triggers
-- ---------------------------------------------------------------------
-- 6a. Order-status functions insert (order_id, sender_id, body) with no
--     conversation_id. Fill it in so those system lines land in the chat.
CREATE OR REPLACE FUNCTION public.messages_fill_conversation()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_buyer uuid; v_farmer uuid; v_listing uuid;
BEGIN
  IF NEW.conversation_id IS NOT NULL THEN RETURN NEW; END IF;
  IF NEW.order_id IS NULL THEN
    RAISE EXCEPTION 'conversation_id is required';
  END IF;

  SELECT buyer_id, farmer_id, listing_id INTO v_buyer, v_farmer, v_listing
  FROM orders WHERE id = NEW.order_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'order not found'; END IF;

  INSERT INTO conversations (buyer_id, seller_id, listing_id)
  VALUES (v_buyer, v_farmer, v_listing)
  ON CONFLICT (buyer_id, seller_id) DO NOTHING;

  SELECT id INTO NEW.conversation_id
  FROM conversations WHERE buyer_id = v_buyer AND seller_id = v_farmer;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_messages_fill_conversation
BEFORE INSERT ON public.messages
FOR EACH ROW EXECUTE FUNCTION public.messages_fill_conversation();

-- 6b. After every message: update conversation preview + notify recipient
CREATE OR REPLACE FUNCTION public.messages_after_insert()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_conv conversations%ROWTYPE; v_recipient uuid; v_name text;
BEGIN
  UPDATE conversations
  SET last_message_at = NEW.created_at,
      last_message_preview = left(NEW.body, 80),
      last_message_sender_id = NEW.sender_id
  WHERE id = NEW.conversation_id
  RETURNING * INTO v_conv;

  IF NEW.sender_id IS NOT NULL AND v_conv.id IS NOT NULL THEN
    v_recipient := CASE WHEN NEW.sender_id = v_conv.buyer_id
                        THEN v_conv.seller_id ELSE v_conv.buyer_id END;
    SELECT full_name INTO v_name FROM profiles WHERE id = NEW.sender_id;
    INSERT INTO notifications (user_id, type, title, body, order_id)
    VALUES (v_recipient, 'new_message',
            'New message from ' || coalesce(v_name, 'AgroMarket user'),
            left(NEW.body, 100), NEW.order_id);
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER trg_messages_after_insert
AFTER INSERT ON public.messages
FOR EACH ROW EXECUTE FUNCTION public.messages_after_insert();

-- ---------------------------------------------------------------------
-- 7. RLS + grants
-- ---------------------------------------------------------------------
ALTER TABLE public.conversations ENABLE ROW LEVEL SECURITY;
ALTER TABLE public.messages      ENABLE ROW LEVEL SECURITY;

REVOKE ALL ON public.conversations, public.messages FROM PUBLIC, anon, authenticated;
GRANT SELECT ON public.conversations TO authenticated;
GRANT SELECT ON public.messages      TO authenticated;
-- clients may only set these four columns when sending
GRANT INSERT (id, conversation_id, sender_id, body) ON public.messages TO authenticated;
GRANT ALL ON public.conversations, public.messages TO service_role;

CREATE POLICY conversations_select ON public.conversations FOR SELECT TO authenticated
USING (auth.uid() IN (buyer_id, seller_id) OR public.chat_is_admin());

CREATE POLICY messages_select ON public.messages FOR SELECT TO authenticated
USING (
  EXISTS (SELECT 1 FROM public.conversations c
          WHERE c.id = messages.conversation_id
            AND auth.uid() IN (c.buyer_id, c.seller_id))
  OR public.chat_is_admin()
);

CREATE POLICY messages_insert ON public.messages FOR INSERT TO authenticated
WITH CHECK (
  sender_id = auth.uid()
  AND EXISTS (SELECT 1 FROM public.conversations c
              WHERE c.id = messages.conversation_id
                AND auth.uid() IN (c.buyer_id, c.seller_id))
);

-- realtime: new messages stream to the app (RLS still applies)
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_publication_tables
                 WHERE pubname = 'supabase_realtime'
                   AND schemaname = 'public' AND tablename = 'messages') THEN
    ALTER PUBLICATION supabase_realtime ADD TABLE public.messages;
  END IF;
END $$;

-- ---------------------------------------------------------------------
-- 8. RPCs (the only functions the app needs)
-- ---------------------------------------------------------------------

-- 8a. Buyer taps "Chat" on a listing -> conversation id
CREATE OR REPLACE FUNCTION public.get_or_create_conversation(p_listing_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_me uuid := auth.uid(); v_seller uuid; v_id uuid;
BEGIN
  IF v_me IS NULL THEN RAISE EXCEPTION 'NOT_AUTHENTICATED'; END IF;
  SELECT farmer_id INTO v_seller FROM listings WHERE id = p_listing_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'LISTING_NOT_FOUND'; END IF;
  IF v_seller = v_me THEN RAISE EXCEPTION 'CANNOT_MESSAGE_SELF'; END IF;

  INSERT INTO conversations (buyer_id, seller_id, listing_id)
  VALUES (v_me, v_seller, p_listing_id)
  ON CONFLICT (buyer_id, seller_id) DO NOTHING;

  SELECT id INTO v_id FROM conversations WHERE buyer_id = v_me AND seller_id = v_seller;
  RETURN v_id;
END $$;

-- 8b. "Chat" button on an order (either party) -> conversation id
CREATE OR REPLACE FUNCTION public.open_order_conversation(p_order_id uuid)
RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_me uuid := auth.uid(); v_o orders%ROWTYPE; v_id uuid;
BEGIN
  IF v_me IS NULL THEN RAISE EXCEPTION 'NOT_AUTHENTICATED'; END IF;
  SELECT * INTO v_o FROM orders WHERE id = p_order_id;
  IF NOT FOUND OR v_me NOT IN (v_o.buyer_id, v_o.farmer_id) THEN
    RAISE EXCEPTION 'ORDER_NOT_FOUND';
  END IF;

  INSERT INTO conversations (buyer_id, seller_id, listing_id)
  VALUES (v_o.buyer_id, v_o.farmer_id, v_o.listing_id)
  ON CONFLICT (buyer_id, seller_id) DO NOTHING;

  SELECT id INTO v_id FROM conversations
  WHERE buyer_id = v_o.buyer_id AND seller_id = v_o.farmer_id;
  RETURN v_id;
END $$;

-- 8c. Inbox (only conversations that have at least one message)
--     Output names match the Android ConversationItem JSON fields.
CREATE OR REPLACE FUNCTION public.get_inbox()
RETURNS TABLE (
  conversation_id uuid,
  listing_id uuid,
  crop_name text,
  counterpart_id uuid,
  counterpart_name text,
  counterpart_avatar text,
  last_message_preview text,
  last_message_sender_id uuid,
  last_message_at timestamptz,
  unread_count integer
)
LANGUAGE sql STABLE SECURITY DEFINER SET search_path = public
AS $$
  SELECT c.id, c.listing_id, l.crop_name,
         p.id, p.full_name, p.avatar_url,
         c.last_message_preview, c.last_message_sender_id, c.last_message_at,
         (SELECT count(*)::int FROM messages m
          WHERE m.conversation_id = c.id AND m.read_at IS NULL
            AND m.sender_id IS NOT NULL AND m.sender_id <> auth.uid())
  FROM conversations c
  JOIN profiles p ON p.id = CASE WHEN c.buyer_id = auth.uid()
                                 THEN c.seller_id ELSE c.buyer_id END
  LEFT JOIN listings l ON l.id = c.listing_id
  WHERE auth.uid() IN (c.buyer_id, c.seller_id)
    AND c.last_message_preview IS NOT NULL
  ORDER BY c.last_message_at DESC;
$$;

-- 8d. Mark the other person's messages as read
CREATE OR REPLACE FUNCTION public.mark_conversation_read(p_conversation_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM conversations
                 WHERE id = p_conversation_id
                   AND auth.uid() IN (buyer_id, seller_id)) THEN
    RAISE EXCEPTION 'CONVERSATION_NOT_FOUND';
  END IF;
  UPDATE messages SET read_at = now()
  WHERE conversation_id = p_conversation_id
    AND read_at IS NULL
    AND sender_id IS DISTINCT FROM auth.uid();
END $$;

-- 8e. Admin dispute viewer (same name/arg the admin web already calls)
CREATE OR REPLACE FUNCTION public.admin_get_dispute_chat(p_order_id uuid)
RETURNS TABLE (id uuid, conversation_id uuid, order_id uuid,
               sender_id uuid, body text, created_at timestamptz)
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE v_o orders%ROWTYPE;
BEGIN
  IF NOT public.chat_is_admin() THEN RAISE EXCEPTION 'FORBIDDEN'; END IF;
  SELECT * INTO v_o FROM orders WHERE id = p_order_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'ORDER_NOT_FOUND'; END IF;

  INSERT INTO audit_log (admin_id, action, entity, entity_id, details)
  VALUES (auth.uid(), 'view_dispute_chat', 'order', p_order_id::text, '{}'::jsonb);

  RETURN QUERY
  SELECT m.id, m.conversation_id, m.order_id, m.sender_id, m.body, m.created_at
  FROM messages m JOIN conversations c ON c.id = m.conversation_id
  WHERE c.buyer_id = v_o.buyer_id AND c.seller_id = v_o.farmer_id
  ORDER BY m.created_at ASC, m.id ASC;
END $$;

REVOKE ALL ON FUNCTION public.get_or_create_conversation(uuid),
                       public.open_order_conversation(uuid),
                       public.get_inbox(),
                       public.mark_conversation_read(uuid),
                       public.admin_get_dispute_chat(uuid),
                       public.chat_is_admin()
  FROM PUBLIC, anon;
GRANT EXECUTE ON FUNCTION public.get_or_create_conversation(uuid),
                          public.open_order_conversation(uuid),
                          public.get_inbox(),
                          public.mark_conversation_read(uuid),
                          public.admin_get_dispute_chat(uuid),
                          public.chat_is_admin()
  TO authenticated, service_role;

COMMIT;

-- After running, reload PostgREST's schema cache:  NOTIFY pgrst, 'reload schema';

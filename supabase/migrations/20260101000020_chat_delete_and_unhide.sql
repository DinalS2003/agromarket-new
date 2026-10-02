-- ---------------------------------------------------------------------
-- 20260101000020_chat_delete_and_unhide.sql
-- Add deleted_by_buyer / deleted_by_seller columns, delete_conversation RPC,
-- un-hide on new message, and filter hidden conversations in get_inbox()
-- ---------------------------------------------------------------------

ALTER TABLE public.conversations
  ADD COLUMN IF NOT EXISTS deleted_by_buyer BOOLEAN DEFAULT FALSE,
  ADD COLUMN IF NOT EXISTS deleted_by_seller BOOLEAN DEFAULT FALSE;

-- Update messages_after_insert to unhide the conversation when a message arrives
CREATE OR REPLACE FUNCTION public.messages_after_insert()
RETURNS trigger
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_conv conversations%ROWTYPE;
  v_recipient uuid;
  v_name text;
BEGIN
  -- Unhide conversation for recipient if it was deleted by them
  UPDATE conversations
  SET last_message_at = NEW.created_at,
      last_message_preview = left(NEW.body, 80),
      last_message_sender_id = NEW.sender_id,
      deleted_by_buyer = CASE WHEN NEW.sender_id = seller_id THEN FALSE ELSE deleted_by_buyer END,
      deleted_by_seller = CASE WHEN NEW.sender_id = buyer_id THEN FALSE ELSE deleted_by_seller END
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

-- RPC to delete conversation for current user
CREATE OR REPLACE FUNCTION public.delete_conversation(p_conversation_id uuid)
RETURNS void
LANGUAGE plpgsql SECURITY DEFINER SET search_path = public
AS $$
DECLARE
  v_me uuid := auth.uid();
  v_conv conversations%ROWTYPE;
BEGIN
  IF v_me IS NULL THEN
    RAISE EXCEPTION 'NOT_AUTHENTICATED';
  END IF;

  SELECT * INTO v_conv FROM conversations WHERE id = p_conversation_id;
  IF NOT FOUND OR v_me NOT IN (v_conv.buyer_id, v_conv.seller_id) THEN
    RAISE EXCEPTION 'CONVERSATION_NOT_FOUND';
  END IF;

  IF v_me = v_conv.buyer_id THEN
    UPDATE conversations SET deleted_by_buyer = TRUE WHERE id = p_conversation_id;
  ELSIF v_me = v_conv.seller_id THEN
    UPDATE conversations SET deleted_by_seller = TRUE WHERE id = p_conversation_id;
  END IF;
END $$;

-- Update get_inbox() to filter out hidden conversations
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
    AND (
      (c.buyer_id = auth.uid() AND c.deleted_by_buyer IS NOT TRUE)
      OR
      (c.seller_id = auth.uid() AND c.deleted_by_seller IS NOT TRUE)
    )
    AND c.last_message_preview IS NOT NULL
  ORDER BY c.last_message_at DESC;
$$;

GRANT EXECUTE ON FUNCTION public.delete_conversation(uuid) TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_inbox() TO authenticated, service_role;

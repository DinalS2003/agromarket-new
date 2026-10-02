-- Migration: 20260101000021_username_password_auth.sql
-- Additive & Reversible: Adds username and credentials support without altering existing schemas

-- 1. Additive columns to profiles
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS username TEXT;
ALTER TABLE profiles ADD COLUMN IF NOT EXISTS has_password BOOLEAN NOT NULL DEFAULT false;

-- 2. Case-insensitive unique index on username
CREATE UNIQUE INDEX IF NOT EXISTS idx_profiles_username_lower 
ON profiles (LOWER(username)) 
WHERE username IS NOT NULL;

-- 3. Check username availability RPC
-- Drop first to allow changing return type from any previous definition
DROP FUNCTION IF EXISTS check_username_available(TEXT);
DROP FUNCTION IF EXISTS check_username_available;

CREATE OR REPLACE FUNCTION check_username_available(p_username TEXT)
RETURNS BOOLEAN
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_clean TEXT := LOWER(TRIM(p_username));
BEGIN
    IF v_clean IS NULL OR LENGTH(v_clean) < 3 OR LENGTH(v_clean) > 20 THEN
        RETURN false;
    END IF;

    IF NOT (v_clean ~ '^[a-z0-9_]{3,20}$') THEN
        RETURN false;
    END IF;

    RETURN NOT EXISTS (
        SELECT 1 FROM profiles WHERE LOWER(username) = v_clean
    );
END;
$$;

GRANT EXECUTE ON FUNCTION check_username_available(TEXT) TO anon, authenticated, service_role;

-- 4. Lookup Login Identifier RPC
DROP FUNCTION IF EXISTS lookup_login_account(TEXT);
DROP FUNCTION IF EXISTS lookup_login_account;

CREATE OR REPLACE FUNCTION lookup_login_account(p_identifier TEXT)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_input TEXT := TRIM(p_identifier);
    v_clean_phone TEXT;
    v_user_id UUID;
    v_username TEXT;
    v_has_password BOOLEAN;
    v_email TEXT;
    v_phone_e164 TEXT;
    v_masked_phone TEXT;
BEGIN
    IF v_input IS NULL OR v_input = '' THEN
        RETURN jsonb_build_object('status', 'INVALID_INPUT');
    END IF;

    -- Check if input is a phone number (e.g. 0771234567, 771234567, or +94771234567)
    IF v_input ~ '^(\+?94|0)?[7][0-9]{8}$' THEN
        -- Normalize phone
        v_clean_phone := regexp_replace(v_input, '[^0-9]', '', 'g');
        IF v_clean_phone LIKE '0%' THEN
            v_clean_phone := '+94' || substr(v_clean_phone, 2);
        ELSIF v_clean_phone LIKE '94%' THEN
            v_clean_phone := '+' || v_clean_phone;
        ELSE
            v_clean_phone := '+94' || v_clean_phone;
        END IF;

        -- Find user by private phone record
        SELECT up.user_id, up.phone_e164, p.username, p.has_password
        INTO v_user_id, v_phone_e164, v_username, v_has_password
        FROM user_private up
        JOIN profiles p ON p.id = up.user_id
        WHERE up.phone_e164 = v_clean_phone
        LIMIT 1;

        IF v_user_id IS NULL THEN
            -- Check in auth.users as fallback
            SELECT id, phone INTO v_user_id, v_phone_e164
            FROM auth.users
            WHERE phone = v_clean_phone
            LIMIT 1;

            IF v_user_id IS NOT NULL THEN
                SELECT username, has_password INTO v_username, v_has_password
                FROM profiles WHERE id = v_user_id;
            END IF;
        END IF;

        IF v_user_id IS NULL THEN
            RETURN jsonb_build_object('status', 'NOT_FOUND');
        END IF;

        SELECT email INTO v_email FROM auth.users WHERE id = v_user_id;
        IF v_email IS NULL THEN
            v_email := 'p' || regexp_replace(v_phone_e164, '[^0-9]', '', 'g') || '@agromarket.lk';
        END IF;

        -- Mask phone for UI (e.g. +94 77 *** **67)
        v_masked_phone := substr(v_phone_e164, 1, 6) || ' *** **' || substr(v_phone_e164, length(v_phone_e164) - 1);

        -- If existing user has no username or password set, trigger the one-time upgrade flow
        IF v_username IS NULL OR v_has_password IS FALSE THEN
            RETURN jsonb_build_object(
                'status', 'NEEDS_UPGRADE',
                'user_id', v_user_id,
                'phone_e164', v_phone_e164,
                'masked_phone', v_masked_phone
            );
        END IF;

        RETURN jsonb_build_object(
            'status', 'OK',
            'user_id', v_user_id,
            'username', v_username,
            'email', v_email,
            'phone_e164', v_phone_e164
        );
    ELSE
        -- Look up by lowercase username
        SELECT p.id, p.username, p.has_password, up.phone_e164
        INTO v_user_id, v_username, v_has_password, v_phone_e164
        FROM profiles p
        LEFT JOIN user_private up ON up.user_id = p.id
        WHERE LOWER(p.username) = LOWER(v_input)
        LIMIT 1;

        IF v_user_id IS NULL THEN
            RETURN jsonb_build_object('status', 'NOT_FOUND');
        END IF;

        SELECT email INTO v_email FROM auth.users WHERE id = v_user_id;
        IF v_email IS NULL AND v_phone_e164 IS NOT NULL THEN
            v_email := 'p' || regexp_replace(v_phone_e164, '[^0-9]', '', 'g') || '@agromarket.lk';
        END IF;

        IF v_phone_e164 IS NOT NULL THEN
            v_masked_phone := substr(v_phone_e164, 1, 6) || ' *** **' || substr(v_phone_e164, length(v_phone_e164) - 1);
        ELSE
            v_masked_phone := 'your mobile';
        END IF;

        RETURN jsonb_build_object(
            'status', 'OK',
            'user_id', v_user_id,
            'username', v_username,
            'email', v_email,
            'phone_e164', v_phone_e164,
            'masked_phone', v_masked_phone
        );
    END IF;
END;
$$;

GRANT EXECUTE ON FUNCTION lookup_login_account(TEXT) TO anon, authenticated, service_role;

-- 5. Register User with Credentials RPC
DROP FUNCTION IF EXISTS register_user_with_credentials(TEXT, TEXT, TEXT, TEXT, TEXT, INT, INT);
DROP FUNCTION IF EXISTS register_user_with_credentials;

CREATE OR REPLACE FUNCTION register_user_with_credentials(
    p_username TEXT,
    p_password TEXT,
    p_full_name TEXT,
    p_nic TEXT,
    p_phone_e164 TEXT,
    p_district_id INT,
    p_city_id INT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_user_id UUID;
    v_clean_phone TEXT;
    v_clean_username TEXT;
    v_email TEXT;
    v_normalized_nic TEXT;
BEGIN
    v_clean_username := LOWER(TRIM(p_username));
    v_clean_phone := regexp_replace(p_phone_e164, '[^0-9+]', '', 'g');
    v_normalized_nic := UPPER(TRIM(p_nic));

    -- Validation
    IF NOT (v_clean_username ~ '^[a-z0-9_]{3,20}$') THEN
        RAISE EXCEPTION 'Username must be 3-20 characters with letters, digits, and underscores only';
    END IF;

    IF EXISTS (SELECT 1 FROM profiles WHERE LOWER(username) = v_clean_username) THEN
        RAISE EXCEPTION 'Username is already taken';
    END IF;

    IF LENGTH(p_password) < 8 THEN
        RAISE EXCEPTION 'Password must be at least 8 characters long';
    END IF;

    IF NOT (v_normalized_nic ~ '^[0-9]{9}[VX]$' OR v_normalized_nic ~ '^[0-9]{12}$') THEN
        RAISE EXCEPTION 'Invalid NIC format. Must be 9 digits followed by V/X or 12 digits';
    END IF;

    IF NOT (v_clean_phone ~ '^\+947[0-9]{8}$') THEN
        RAISE EXCEPTION 'Invalid Sri Lankan mobile format. Must be +947XXXXXXXX';
    END IF;

    -- Generate internal auth email
    v_email := 'p' || regexp_replace(v_clean_phone, '[^0-9]', '', 'g') || '@agromarket.lk';

    -- Find existing auth user or create new
    SELECT id INTO v_user_id FROM auth.users WHERE email = v_email OR phone = v_clean_phone LIMIT 1;

    IF v_user_id IS NULL THEN
        v_user_id := gen_random_uuid();
        INSERT INTO auth.users (
            id, instance_id, aud, role, email, encrypted_password,
            email_confirmed_at, phone, phone_confirmed_at,
            confirmation_token, recovery_token, email_change,
            email_change_token_new, email_change_token_current,
            phone_change, phone_change_token, reauthentication_token,
            raw_app_meta_data, raw_user_meta_data, created_at, updated_at
        ) VALUES (
            v_user_id,
            '00000000-0000-0000-0000-000000000000',
            'authenticated',
            'authenticated',
            v_email,
            crypt(p_password, gen_salt('bf')),
            NOW(),
            v_clean_phone,
            NOW(),
            '', '', '', '', '', '', '', '',
            '{"provider":"email","providers":["email"]}'::jsonb,
            jsonb_build_object('phone', v_clean_phone, 'username', v_clean_username),
            NOW(),
            NOW()
        );

        INSERT INTO auth.identities (
            id, user_id, identity_data, provider, provider_id,
            last_sign_in_at, created_at, updated_at
        ) VALUES (
            gen_random_uuid(),
            v_user_id,
            jsonb_build_object('sub', v_user_id::text, 'email', v_email),
            'email',
            v_user_id::text,
            NOW(), NOW(), NOW()
        ) ON CONFLICT (provider_id, provider) DO NOTHING;
    ELSE
        -- Update password and phone confirmation
        UPDATE auth.users
        SET encrypted_password = crypt(p_password, gen_salt('bf')),
            phone = v_clean_phone,
            phone_confirmed_at = NOW(),
            raw_user_meta_data = raw_user_meta_data || jsonb_build_object('username', v_clean_username),
            updated_at = NOW()
        WHERE id = v_user_id;
    END IF;

    -- Upsert profile with username and has_password = true
    INSERT INTO profiles (id, full_name, district_id, city_id, username, has_password)
    VALUES (v_user_id, TRIM(p_full_name), p_district_id, p_city_id, v_clean_username, true)
    ON CONFLICT (id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        district_id = EXCLUDED.district_id,
        city_id = EXCLUDED.city_id,
        username = EXCLUDED.username,
        has_password = true,
        updated_at = NOW();

    -- Upsert user_private
    INSERT INTO user_private (user_id, nic, phone_e164)
    VALUES (v_user_id, v_normalized_nic, v_clean_phone)
    ON CONFLICT (user_id) DO UPDATE SET
        nic = EXCLUDED.nic,
        phone_e164 = EXCLUDED.phone_e164;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_user_id,
        'username', v_clean_username,
        'email', v_email
    );
END;
$$;

GRANT EXECUTE ON FUNCTION register_user_with_credentials(TEXT, TEXT, TEXT, TEXT, TEXT, INT, INT) TO anon, authenticated, service_role;

-- 6. Upgrade Existing User Credentials RPC (One-time upgrade)
DROP FUNCTION IF EXISTS upgrade_user_credentials(UUID, TEXT, TEXT);
DROP FUNCTION IF EXISTS upgrade_user_credentials;

CREATE OR REPLACE FUNCTION upgrade_user_credentials(
    p_user_id UUID,
    p_username TEXT,
    p_password TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_clean_username TEXT := LOWER(TRIM(p_username));
    v_email TEXT;
BEGIN
    IF NOT (v_clean_username ~ '^[a-z0-9_]{3,20}$') THEN
        RAISE EXCEPTION 'Username must be 3-20 characters with letters, digits, and underscores only';
    END IF;

    IF EXISTS (SELECT 1 FROM profiles WHERE LOWER(username) = v_clean_username AND id != p_user_id) THEN
        RAISE EXCEPTION 'Username is already taken';
    END IF;

    IF LENGTH(p_password) < 8 THEN
        RAISE EXCEPTION 'Password must be at least 8 characters long';
    END IF;

    -- Update Supabase Auth user password
    UPDATE auth.users
    SET encrypted_password = crypt(p_password, gen_salt('bf')),
        updated_at = NOW()
    WHERE id = p_user_id
    RETURNING email INTO v_email;

    -- Update profile
    UPDATE profiles
    SET username = v_clean_username,
        has_password = true,
        updated_at = NOW()
    WHERE id = p_user_id;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', p_user_id,
        'username', v_clean_username,
        'email', v_email
    );
END;
$$;

GRANT EXECUTE ON FUNCTION upgrade_user_credentials(UUID, TEXT, TEXT) TO anon, authenticated, service_role;

-- 7. Reset Password RPC (Used after SMS OTP verification)
DROP FUNCTION IF EXISTS reset_user_password(UUID, TEXT);
DROP FUNCTION IF EXISTS reset_user_password;

CREATE OR REPLACE FUNCTION reset_user_password(
    p_user_id UUID,
    p_new_password TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_email TEXT;
BEGIN
    IF LENGTH(p_new_password) < 8 THEN
        RAISE EXCEPTION 'Password must be at least 8 characters long';
    END IF;

    UPDATE auth.users
    SET encrypted_password = crypt(p_new_password, gen_salt('bf')),
        updated_at = NOW()
    WHERE id = p_user_id
    RETURNING email INTO v_email;

    UPDATE profiles
    SET has_password = true,
        updated_at = NOW()
    WHERE id = p_user_id;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', p_user_id,
        'email', v_email
    );
END;
$$;

GRANT EXECUTE ON FUNCTION reset_user_password(UUID, TEXT) TO anon, authenticated, service_role;

-- 8. Change Password RPC (Profile settings: requires old password verification)
DROP FUNCTION IF EXISTS change_user_password(TEXT, TEXT);
DROP FUNCTION IF EXISTS change_user_password;

CREATE OR REPLACE FUNCTION change_user_password(
    p_old_password TEXT,
    p_new_password TEXT
)
RETURNS JSONB
LANGUAGE plpgsql
SECURITY DEFINER
AS $$
DECLARE
    v_uid UUID := auth.uid();
    v_current_hash TEXT;
BEGIN
    IF v_uid IS NULL THEN
        RAISE EXCEPTION 'Not authenticated';
    END IF;

    IF LENGTH(p_new_password) < 8 THEN
        RAISE EXCEPTION 'New password must be at least 8 characters long';
    END IF;

    SELECT encrypted_password INTO v_current_hash FROM auth.users WHERE id = v_uid;
    IF v_current_hash IS NULL THEN
        RAISE EXCEPTION 'User account not found';
    END IF;

    -- Verify old password
    IF v_current_hash != crypt(p_old_password, v_current_hash) THEN
        RAISE EXCEPTION 'Current password is incorrect';
    END IF;

    -- Update to new password
    UPDATE auth.users
    SET encrypted_password = crypt(p_new_password, gen_salt('bf')),
        updated_at = NOW()
    WHERE id = v_uid;

    UPDATE profiles
    SET has_password = true,
        updated_at = NOW()
    WHERE id = v_uid;

    RETURN jsonb_build_object('success', true);
END;
$$;

GRANT EXECUTE ON FUNCTION change_user_password(TEXT, TEXT) TO authenticated, service_role;

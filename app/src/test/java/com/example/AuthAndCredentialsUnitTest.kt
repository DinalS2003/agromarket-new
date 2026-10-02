package com.example

import com.example.data.models.UserProfile
import com.example.viewmodel.PasswordStrength
import com.example.viewmodel.UsernameCheckStatus
import com.example.viewmodel.evaluatePasswordStrength
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.*
import org.junit.Test

class AuthAndCredentialsUnitTest {

    @Test
    fun testUsernameFormatValidation() {
        val usernameRegex = Regex("^[a-z0-9_]{3,20}$")

        // Valid usernames
        assertTrue("valid user 1", "farmer_saman".matches(usernameRegex))
        assertTrue("valid user 2", "buyer123".matches(usernameRegex))
        assertTrue("valid user 3", "abc".matches(usernameRegex))
        assertTrue("valid user 4", "a_b_c_1_2_3_4_5_6_7_".matches(usernameRegex)) // exactly 20 chars

        // Invalid usernames
        assertFalse("too short", "ab".matches(usernameRegex))
        assertFalse("too long", "a_b_c_1_2_3_4_5_6_7_8_9_0".matches(usernameRegex))
        assertFalse("contains uppercase", "Farmer_Saman".matches(usernameRegex))
        assertFalse("contains space", "farmer saman".matches(usernameRegex))
        assertFalse("contains dash", "farmer-saman".matches(usernameRegex))
        assertFalse("contains at symbol", "user@domain".matches(usernameRegex))
    }

    @Test
    fun testEvaluatePasswordStrength() {
        assertEquals(PasswordStrength.NONE, evaluatePasswordStrength(""))
        assertEquals(PasswordStrength.WEAK, evaluatePasswordStrength("short"))
        assertEquals(PasswordStrength.WEAK, evaluatePasswordStrength("1234567"))
        
        // At least 8 chars with mixed attributes
        val fairPass = evaluatePasswordStrength("password")
        assertTrue(fairPass == PasswordStrength.WEAK || fairPass == PasswordStrength.FAIR)
        
        val goodPass = evaluatePasswordStrength("Password123")
        assertTrue(goodPass == PasswordStrength.GOOD || goodPass == PasswordStrength.STRONG)

        val strongPass = evaluatePasswordStrength("AgroMarket@2026_Secure!")
        assertEquals(PasswordStrength.STRONG, strongPass)
    }

    @Test
    fun testLoginIdentifierAutoDetection() {
        fun isPhoneIdentifier(raw: String): Boolean {
            val clean = raw.trim().replace(" ", "").replace("-", "")
            return clean.matches(Regex("^(\\+94|0)?[0-9]{9,10}$"))
        }

        assertTrue("+94771234567 is phone", isPhoneIdentifier("+94771234567"))
        assertTrue("0771234567 is phone", isPhoneIdentifier("0771234567"))
        assertTrue("771234567 is phone", isPhoneIdentifier("771234567"))
        assertTrue("with spaces is phone", isPhoneIdentifier("+94 77 123 4567"))
        assertTrue("with dashes is phone", isPhoneIdentifier("077-123-4567"))

        assertFalse("username with digits is not phone", isPhoneIdentifier("farmer123"))
        assertFalse("plain username is not phone", isPhoneIdentifier("dinal_sanjula"))
        assertFalse("username with symbols is not phone", isPhoneIdentifier("organic_veggies"))
    }

    @Test
    fun testUserProfileModelWithUsernameAndHasPassword() {
        val moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
        val adapter = moshi.adapter(UserProfile::class.java)

        val json = """
            {
                "id": "user-uuid-123",
                "full_name": "Saman Kumara",
                "district_id": 1,
                "city_id": 1,
                "username": "saman_farmer",
                "has_password": true
            }
        """.trimIndent()

        val profile = adapter.fromJson(json)
        assertNotNull(profile)
        assertEquals("user-uuid-123", profile?.id)
        assertEquals("Saman Kumara", profile?.fullName)
        assertEquals("saman_farmer", profile?.username)
        assertEquals(true, profile?.hasPassword)
    }

    @Test
    fun testExistingUserNeedsUpgradeDetection() {
        // Old user profile without credentials has has_password = false and username = null
        val profileOld = UserProfile(
            id = "old-user-uuid",
            fullName = "Old User",
            districtId = 1,
            cityId = 1,
            username = null,
            hasPassword = false
        )

        val needsUpgrade = profileOld.hasPassword == false || profileOld.username.isNullOrBlank()
        assertTrue("Old user should require one-time upgrade flow", needsUpgrade)

        // New or upgraded user profile
        val profileUpgraded = UserProfile(
            id = "upgraded-user-uuid",
            fullName = "Upgraded User",
            districtId = 1,
            cityId = 1,
            username = "upgraded_user",
            hasPassword = true
        )

        val needsUpgradeUpgraded = profileUpgraded.hasPassword == false || profileUpgraded.username.isNullOrBlank()
        assertFalse("Upgraded user should not require upgrade flow", needsUpgradeUpgraded)
    }
}

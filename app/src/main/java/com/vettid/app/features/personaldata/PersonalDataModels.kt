package com.vettid.app.features.personaldata

import java.time.Instant

/**
 * Represents a personal data item stored in the vault.
 *
 * Data sensitivity types control how data can be shared:
 * - public: Can be shared freely (text displayed)
 * - private: Shared only with consent/contract (masked)
 * - key: Cryptographic keys (masked, configurable sharing)
 * - minor_secret: Never shared with connections (masked)
 *
 * The isInPublicProfile flag independently controls whether this field
 * appears in the user's public profile visible to connections.
 */
data class PersonalDataItem(
    val id: String,
    val name: String,
    val type: DataType,
    val value: String,
    val category: DataCategory? = null,
    val fieldType: FieldType = FieldType.TEXT,
    val isSystemField: Boolean = false,
    val isInPublicProfile: Boolean = false,  // Whether to include in public profile
    val isSensitive: Boolean = false,  // Whether to mask value (PASSWORD type fields)
    val sortOrder: Int = 0,  // Order within category (lower = higher up)
    val createdAt: Instant,
    val updatedAt: Instant
)

/**
 * Field types that define how data is stored and displayed.
 */
enum class FieldType(val displayName: String, val description: String) {
    TEXT("Text", "General text value"),
    PASSWORD("Password", "Masked/hidden value"),
    NUMBER("Number", "Numeric value"),
    DATE("Date", "Date value (YYYY-MM-DD)"),
    EMAIL("Email", "Email address"),
    PHONE("Phone", "Phone number"),
    URL("URL", "Web address"),
    NOTE("Note", "Multi-line text")
}

/**
 * Data visibility types per mobile-ui-plan.md Section 5.2
 */
enum class DataType(val displayName: String, val description: String) {
    PUBLIC("Public", "Shared with all connections"),
    PRIVATE("Private", "Shared only with consent"),
    KEY("Key", "Cryptographic keys"),
    MINOR_SECRET("Minor Secret", "Never shared")
}

/**
 * Categories for organizing personal data.
 */
enum class DataCategory(val displayName: String, val iconName: String) {
    IDENTITY("Identity", "person"),
    CONTACT("Contact", "phone"),
    ADDRESS("Address", "location_on"),
    FINANCIAL("Financial", "account_balance"),
    MEDICAL("Medical", "medical_services"),
    PROFESSIONAL("Professional", "work"),
    EDUCATION("Education", "school"),
    VEHICLE("Vehicle", "directions_car"),
    LEGAL("Legal", "gavel"),
    DIGITAL("Digital", "language"),
    TRAVEL("Travel", "flight_takeoff"),
    MEMBERSHIP("Membership", "card_membership"),
    PROPERTY("Property", "home"),
    OTHER("Other", "category")
}

/**
 * Template for common personal data fields.
 * Provides standardized naming conventions and appropriate field types.
 */
data class PersonalDataTemplate(
    val name: String,
    val category: DataCategory,
    val fieldType: FieldType,
    val description: String
)

/**
 * Standard templates for common personal data fields.
 * Using consistent naming helps with data interoperability and sharing.
 */
object PersonalDataTemplates {
    val templates = listOf(
        // Identity
        PersonalDataTemplate("Date of Birth", DataCategory.IDENTITY, FieldType.DATE, "Your birth date"),
        PersonalDataTemplate("Social Security Number", DataCategory.IDENTITY, FieldType.TEXT, "SSN (US)"),
        PersonalDataTemplate("National ID", DataCategory.IDENTITY, FieldType.TEXT, "Government-issued ID number"),
        PersonalDataTemplate("Passport Number", DataCategory.IDENTITY, FieldType.TEXT, "Passport document number"),
        PersonalDataTemplate("Driver License", DataCategory.IDENTITY, FieldType.TEXT, "Driver's license number"),
        PersonalDataTemplate("Place of Birth", DataCategory.IDENTITY, FieldType.TEXT, "City/country of birth"),
        PersonalDataTemplate("Nationality", DataCategory.IDENTITY, FieldType.TEXT, "Your nationality/citizenship"),
        PersonalDataTemplate("Gender", DataCategory.IDENTITY, FieldType.TEXT, "Your gender identity"),
        PersonalDataTemplate("Preferred Name / Nickname", DataCategory.IDENTITY, FieldType.TEXT, "Name you go by"),
        PersonalDataTemplate("Maiden Name", DataCategory.IDENTITY, FieldType.TEXT, "Birth surname if changed"),
        PersonalDataTemplate("Marital Status", DataCategory.IDENTITY, FieldType.TEXT, "Single, Married, etc."),
        PersonalDataTemplate("Spouse Name", DataCategory.IDENTITY, FieldType.TEXT, "Spouse's full name"),
        PersonalDataTemplate("Number of Dependents", DataCategory.IDENTITY, FieldType.NUMBER, "Dependent count (tax-relevant)"),
        PersonalDataTemplate("Eye Color", DataCategory.IDENTITY, FieldType.TEXT, "Eye color (for ID matching)"),
        PersonalDataTemplate("Height", DataCategory.IDENTITY, FieldType.TEXT, "Height (for ID matching)"),
        PersonalDataTemplate("Weight", DataCategory.IDENTITY, FieldType.TEXT, "Weight (for ID matching)"),

        // Contact
        PersonalDataTemplate("Mobile Phone", DataCategory.CONTACT, FieldType.PHONE, "Primary mobile number"),
        PersonalDataTemplate("Home Phone", DataCategory.CONTACT, FieldType.PHONE, "Home landline number"),
        PersonalDataTemplate("Work Phone", DataCategory.CONTACT, FieldType.PHONE, "Work/office number"),
        PersonalDataTemplate("Personal Email", DataCategory.CONTACT, FieldType.EMAIL, "Personal email address"),
        PersonalDataTemplate("Work Email", DataCategory.CONTACT, FieldType.EMAIL, "Work/business email"),
        PersonalDataTemplate("Website", DataCategory.CONTACT, FieldType.URL, "Personal website URL"),
        PersonalDataTemplate("LinkedIn", DataCategory.CONTACT, FieldType.URL, "LinkedIn profile URL"),
        PersonalDataTemplate("Fax Number", DataCategory.CONTACT, FieldType.PHONE, "Fax number (legal/medical)"),
        PersonalDataTemplate("WhatsApp Number", DataCategory.CONTACT, FieldType.PHONE, "WhatsApp contact"),
        PersonalDataTemplate("Signal Number", DataCategory.CONTACT, FieldType.PHONE, "Signal messenger number"),
        PersonalDataTemplate("Telegram Handle", DataCategory.CONTACT, FieldType.TEXT, "Telegram username"),
        PersonalDataTemplate("Skype ID", DataCategory.CONTACT, FieldType.TEXT, "Skype username"),

        // Address
        PersonalDataTemplate("Home Address", DataCategory.ADDRESS, FieldType.NOTE, "Full residential address"),
        PersonalDataTemplate("Mailing Address", DataCategory.ADDRESS, FieldType.NOTE, "Postal/mailing address"),
        PersonalDataTemplate("Work Address", DataCategory.ADDRESS, FieldType.NOTE, "Office/work address"),
        PersonalDataTemplate("City", DataCategory.ADDRESS, FieldType.TEXT, "City of residence"),
        PersonalDataTemplate("State/Province", DataCategory.ADDRESS, FieldType.TEXT, "State or province"),
        PersonalDataTemplate("Postal Code", DataCategory.ADDRESS, FieldType.TEXT, "ZIP or postal code"),
        PersonalDataTemplate("Country", DataCategory.ADDRESS, FieldType.TEXT, "Country of residence"),

        // Financial
        PersonalDataTemplate("Bank Name", DataCategory.FINANCIAL, FieldType.TEXT, "Primary bank name"),
        PersonalDataTemplate("Bank Account", DataCategory.FINANCIAL, FieldType.TEXT, "Account number"),
        PersonalDataTemplate("Routing Number", DataCategory.FINANCIAL, FieldType.TEXT, "Bank routing/ABA number"),
        PersonalDataTemplate("IBAN", DataCategory.FINANCIAL, FieldType.TEXT, "International bank account number"),
        PersonalDataTemplate("Tax ID", DataCategory.FINANCIAL, FieldType.TEXT, "Tax identification number"),

        // Medical
        PersonalDataTemplate("Blood Type", DataCategory.MEDICAL, FieldType.TEXT, "Your blood type (e.g., A+, O-)"),
        PersonalDataTemplate("Allergies", DataCategory.MEDICAL, FieldType.NOTE, "Known allergies"),
        PersonalDataTemplate("Medical Conditions", DataCategory.MEDICAL, FieldType.NOTE, "Relevant medical conditions"),
        PersonalDataTemplate("Emergency Contact", DataCategory.MEDICAL, FieldType.TEXT, "Emergency contact name"),
        PersonalDataTemplate("Emergency Phone", DataCategory.MEDICAL, FieldType.PHONE, "Emergency contact phone"),
        PersonalDataTemplate("Insurance Provider", DataCategory.MEDICAL, FieldType.TEXT, "Health insurance company"),
        PersonalDataTemplate("Insurance ID", DataCategory.MEDICAL, FieldType.TEXT, "Insurance policy/member ID"),
        PersonalDataTemplate("Primary Physician", DataCategory.MEDICAL, FieldType.TEXT, "Primary care doctor's name"),
        PersonalDataTemplate("Medications", DataCategory.MEDICAL, FieldType.NOTE, "Current medications and dosages"),
        PersonalDataTemplate("Pharmacy Name", DataCategory.MEDICAL, FieldType.TEXT, "Preferred pharmacy"),
        PersonalDataTemplate("Pharmacy Phone", DataCategory.MEDICAL, FieldType.PHONE, "Pharmacy phone number"),
        PersonalDataTemplate("Organ Donor Status", DataCategory.MEDICAL, FieldType.TEXT, "Yes/No/Registered"),
        PersonalDataTemplate("Advance Directive Location", DataCategory.MEDICAL, FieldType.TEXT, "Where directive is stored"),
        PersonalDataTemplate("Dentist Name", DataCategory.MEDICAL, FieldType.TEXT, "Dentist's name"),
        PersonalDataTemplate("Dentist Phone", DataCategory.MEDICAL, FieldType.PHONE, "Dentist's phone"),
        PersonalDataTemplate("Vision Prescription", DataCategory.MEDICAL, FieldType.NOTE, "Current lens prescription"),
        PersonalDataTemplate("Medical Power of Attorney", DataCategory.MEDICAL, FieldType.TEXT, "Healthcare proxy name"),
        PersonalDataTemplate("Health Insurance Group", DataCategory.MEDICAL, FieldType.TEXT, "Group number"),
        PersonalDataTemplate("Dental Insurance Provider", DataCategory.MEDICAL, FieldType.TEXT, "Dental insurance company"),
        PersonalDataTemplate("Dental Insurance ID", DataCategory.MEDICAL, FieldType.TEXT, "Dental member ID"),
        PersonalDataTemplate("Vision Insurance Provider", DataCategory.MEDICAL, FieldType.TEXT, "Vision insurance company"),
        PersonalDataTemplate("Vision Insurance ID", DataCategory.MEDICAL, FieldType.TEXT, "Vision member ID"),

        // Professional
        PersonalDataTemplate("Employer", DataCategory.PROFESSIONAL, FieldType.TEXT, "Current employer name"),
        PersonalDataTemplate("Job Title", DataCategory.PROFESSIONAL, FieldType.TEXT, "Current position/role"),
        PersonalDataTemplate("Work ID / Badge Number", DataCategory.PROFESSIONAL, FieldType.TEXT, "Employee identification"),
        PersonalDataTemplate("Department", DataCategory.PROFESSIONAL, FieldType.TEXT, "Department or division"),
        PersonalDataTemplate("Manager Name", DataCategory.PROFESSIONAL, FieldType.TEXT, "Direct supervisor name"),
        PersonalDataTemplate("Manager Email", DataCategory.PROFESSIONAL, FieldType.EMAIL, "Supervisor's email"),
        PersonalDataTemplate("Start Date", DataCategory.PROFESSIONAL, FieldType.DATE, "Employment start date"),
        PersonalDataTemplate("Professional License", DataCategory.PROFESSIONAL, FieldType.TEXT, "License type and number"),
        PersonalDataTemplate("License Expiration", DataCategory.PROFESSIONAL, FieldType.DATE, "License expiry date"),
        PersonalDataTemplate("Professional LinkedIn", DataCategory.PROFESSIONAL, FieldType.URL, "Professional profile URL"),

        // Education
        PersonalDataTemplate("School Name", DataCategory.EDUCATION, FieldType.TEXT, "Institution name"),
        PersonalDataTemplate("Degree", DataCategory.EDUCATION, FieldType.TEXT, "Degree earned (e.g., BS Computer Science)"),
        PersonalDataTemplate("Graduation Date", DataCategory.EDUCATION, FieldType.DATE, "Date of graduation"),
        PersonalDataTemplate("Student ID", DataCategory.EDUCATION, FieldType.TEXT, "Student identification number"),
        PersonalDataTemplate("GPA", DataCategory.EDUCATION, FieldType.TEXT, "Grade point average"),
        PersonalDataTemplate("Major", DataCategory.EDUCATION, FieldType.TEXT, "Primary field of study"),
        PersonalDataTemplate("Minor", DataCategory.EDUCATION, FieldType.TEXT, "Secondary field of study"),

        // Vehicle
        PersonalDataTemplate("Vehicle Make", DataCategory.VEHICLE, FieldType.TEXT, "Manufacturer (e.g., Toyota)"),
        PersonalDataTemplate("Vehicle Model", DataCategory.VEHICLE, FieldType.TEXT, "Model name (e.g., Camry)"),
        PersonalDataTemplate("Vehicle Year", DataCategory.VEHICLE, FieldType.TEXT, "Model year"),
        PersonalDataTemplate("VIN", DataCategory.VEHICLE, FieldType.TEXT, "Vehicle Identification Number"),
        PersonalDataTemplate("License Plate", DataCategory.VEHICLE, FieldType.TEXT, "Plate number and state"),
        PersonalDataTemplate("Vehicle Color", DataCategory.VEHICLE, FieldType.TEXT, "Exterior color"),
        PersonalDataTemplate("Registration Expiry", DataCategory.VEHICLE, FieldType.DATE, "Registration expiration date"),

        // Legal
        PersonalDataTemplate("Attorney Name", DataCategory.LEGAL, FieldType.TEXT, "Primary attorney"),
        PersonalDataTemplate("Attorney Phone", DataCategory.LEGAL, FieldType.PHONE, "Attorney's phone"),
        PersonalDataTemplate("Attorney Email", DataCategory.LEGAL, FieldType.EMAIL, "Attorney's email"),
        PersonalDataTemplate("Power of Attorney", DataCategory.LEGAL, FieldType.TEXT, "POA holder name"),
        PersonalDataTemplate("Beneficiary Name", DataCategory.LEGAL, FieldType.TEXT, "Primary beneficiary"),
        PersonalDataTemplate("Beneficiary Relationship", DataCategory.LEGAL, FieldType.TEXT, "Relationship to beneficiary"),
        PersonalDataTemplate("Will Location", DataCategory.LEGAL, FieldType.NOTE, "Where will/trust documents are stored"),

        // Digital
        PersonalDataTemplate("GitHub Username", DataCategory.DIGITAL, FieldType.TEXT, "GitHub handle"),
        PersonalDataTemplate("Twitter/X Handle", DataCategory.DIGITAL, FieldType.TEXT, "Social media handle"),
        PersonalDataTemplate("Discord Username", DataCategory.DIGITAL, FieldType.TEXT, "Discord identifier"),
        PersonalDataTemplate("Apple ID Email", DataCategory.DIGITAL, FieldType.EMAIL, "Apple account email"),
        PersonalDataTemplate("Google Account", DataCategory.DIGITAL, FieldType.EMAIL, "Google/Gmail account"),
        PersonalDataTemplate("Microsoft Account", DataCategory.DIGITAL, FieldType.EMAIL, "Microsoft account email"),
        PersonalDataTemplate("Personal Domain", DataCategory.DIGITAL, FieldType.URL, "Owned domain name"),
        PersonalDataTemplate("Recovery Email", DataCategory.DIGITAL, FieldType.EMAIL, "Account recovery email"),

        // Travel
        PersonalDataTemplate("Known Traveler Number", DataCategory.TRAVEL, FieldType.TEXT, "TSA PreCheck / Global Entry"),
        PersonalDataTemplate("Frequent Flyer Number", DataCategory.TRAVEL, FieldType.TEXT, "Airline loyalty ID"),
        PersonalDataTemplate("Airline Preference", DataCategory.TRAVEL, FieldType.TEXT, "Preferred airline"),
        PersonalDataTemplate("Hotel Loyalty Number", DataCategory.TRAVEL, FieldType.TEXT, "Hotel rewards ID"),
        PersonalDataTemplate("Rental Car Loyalty", DataCategory.TRAVEL, FieldType.TEXT, "Car rental rewards ID"),
        PersonalDataTemplate("Visa Number", DataCategory.TRAVEL, FieldType.TEXT, "Travel visa number"),
        PersonalDataTemplate("Visa Expiry", DataCategory.TRAVEL, FieldType.DATE, "Visa expiration date"),
        PersonalDataTemplate("Visa Country", DataCategory.TRAVEL, FieldType.TEXT, "Country that issued visa"),

        // Membership
        PersonalDataTemplate("Membership Name", DataCategory.MEMBERSHIP, FieldType.TEXT, "Organization or program name"),
        PersonalDataTemplate("Member ID", DataCategory.MEMBERSHIP, FieldType.TEXT, "Membership number"),
        PersonalDataTemplate("Membership Type", DataCategory.MEMBERSHIP, FieldType.TEXT, "Level/tier (e.g., Gold, Premium)"),
        PersonalDataTemplate("Expiration Date", DataCategory.MEMBERSHIP, FieldType.DATE, "Membership expiry"),
        PersonalDataTemplate("Library Card Number", DataCategory.MEMBERSHIP, FieldType.TEXT, "Library card ID"),

        // Property
        PersonalDataTemplate("Property Address", DataCategory.PROPERTY, FieldType.NOTE, "Full property address"),
        PersonalDataTemplate("Property Type", DataCategory.PROPERTY, FieldType.TEXT, "House, Condo, Apartment, etc."),
        PersonalDataTemplate("Mortgage Lender", DataCategory.PROPERTY, FieldType.TEXT, "Mortgage company name"),
        PersonalDataTemplate("Mortgage Account", DataCategory.PROPERTY, FieldType.TEXT, "Mortgage account number"),
        PersonalDataTemplate("HOA Name", DataCategory.PROPERTY, FieldType.TEXT, "HOA organization name"),
        PersonalDataTemplate("HOA Account", DataCategory.PROPERTY, FieldType.TEXT, "HOA account number"),
        PersonalDataTemplate("Landlord Name", DataCategory.PROPERTY, FieldType.TEXT, "Landlord or property manager"),
        PersonalDataTemplate("Landlord Phone", DataCategory.PROPERTY, FieldType.PHONE, "Landlord phone"),
        PersonalDataTemplate("Lease End Date", DataCategory.PROPERTY, FieldType.DATE, "Lease expiration")
    )

    /** Get templates for a specific category */
    fun forCategory(category: DataCategory): List<PersonalDataTemplate> =
        templates.filter { it.category == category }
}

// MARK: - Multi-Field Templates

/**
 * Input hint for multi-field template fields.
 * Controls which widget is used in the template form.
 */
enum class PersonalDataFieldInputHint {
    TEXT,           // Standard text with word capitalization
    DATE,           // Date picker (MM/DD/YYYY)
    EXPIRY_DATE,    // Expiry date picker (MM/YYYY)
    COUNTRY,        // Country dropdown
    STATE,          // US state / province dropdown
    NUMBER,         // Numeric keyboard
    PHONE,          // Phone number input
    EMAIL           // Email keyboard
}

/**
 * A field definition within a multi-field personal data template.
 */
data class PersonalDataTemplateField(
    val name: String,
    val namespace: String,
    val category: DataCategory,
    val placeholder: String = "",
    val inputHint: PersonalDataFieldInputHint = PersonalDataFieldInputHint.TEXT
)

/**
 * A pre-defined collection of fields for common personal data types.
 * Similar to SecretTemplate but for personal data.
 */
data class PersonalDataMultiTemplate(
    val name: String,
    val description: String,
    val category: DataCategory,
    val fields: List<PersonalDataTemplateField>
) {
    companion object {
        val all = listOf(
            PersonalDataMultiTemplate(
                name = "Home Address",
                description = "Street, city, state, postal code, country",
                category = DataCategory.ADDRESS,
                fields = listOf(
                    PersonalDataTemplateField("Street", "address.home.street", DataCategory.ADDRESS, "e.g., 123 Main St"),
                    PersonalDataTemplateField("Street Line 2", "address.home.street2", DataCategory.ADDRESS, "Apt, Suite, etc."),
                    PersonalDataTemplateField("City", "address.home.city", DataCategory.ADDRESS, "e.g., San Francisco"),
                    PersonalDataTemplateField("State / Province", "address.home.state", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.STATE),
                    PersonalDataTemplateField("Postal Code", "address.home.postal_code", DataCategory.ADDRESS, "e.g., 94102", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Country", "address.home.country", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.COUNTRY)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Business Address",
                description = "Company name, street, city, state, postal code, country",
                category = DataCategory.ADDRESS,
                fields = listOf(
                    PersonalDataTemplateField("Company", "address.work.company", DataCategory.ADDRESS, "e.g., Acme Inc."),
                    PersonalDataTemplateField("Street", "address.work.street", DataCategory.ADDRESS, "e.g., 456 Office Blvd"),
                    PersonalDataTemplateField("Street Line 2", "address.work.street2", DataCategory.ADDRESS, "Floor, Suite, etc."),
                    PersonalDataTemplateField("City", "address.work.city", DataCategory.ADDRESS, "e.g., New York"),
                    PersonalDataTemplateField("State / Province", "address.work.state", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.STATE),
                    PersonalDataTemplateField("Postal Code", "address.work.postal_code", DataCategory.ADDRESS, "e.g., 10001", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Country", "address.work.country", DataCategory.ADDRESS, "", PersonalDataFieldInputHint.COUNTRY)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Family Member",
                description = "Name, relationship, phone, email",
                category = DataCategory.CONTACT,
                fields = listOf(
                    PersonalDataTemplateField("Full Name", "contact.family.name", DataCategory.CONTACT, "e.g., Jane Doe"),
                    PersonalDataTemplateField("Relationship", "contact.family.relationship", DataCategory.CONTACT, "e.g., Spouse, Parent"),
                    PersonalDataTemplateField("Phone", "contact.family.phone", DataCategory.CONTACT, "", PersonalDataFieldInputHint.PHONE),
                    PersonalDataTemplateField("Email", "contact.family.email", DataCategory.CONTACT, "", PersonalDataFieldInputHint.EMAIL)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Emergency Contact",
                description = "Name, relationship, phone",
                category = DataCategory.MEDICAL,
                fields = listOf(
                    PersonalDataTemplateField("Name", "medical.emergency.name", DataCategory.MEDICAL, "e.g., John Smith"),
                    PersonalDataTemplateField("Relationship", "medical.emergency.relationship", DataCategory.MEDICAL, "e.g., Spouse, Parent"),
                    PersonalDataTemplateField("Phone", "medical.emergency.phone", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.PHONE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Full Name",
                description = "Prefix, first, middle, last, suffix",
                category = DataCategory.IDENTITY,
                fields = listOf(
                    PersonalDataTemplateField("Prefix", "personal.legal.prefix", DataCategory.IDENTITY, "e.g., Mr., Ms., Dr."),
                    PersonalDataTemplateField("First Name", "personal.legal.first_name", DataCategory.IDENTITY, ""),
                    PersonalDataTemplateField("Middle Name", "personal.legal.middle_name", DataCategory.IDENTITY, ""),
                    PersonalDataTemplateField("Last Name", "personal.legal.last_name", DataCategory.IDENTITY, ""),
                    PersonalDataTemplateField("Suffix", "personal.legal.suffix", DataCategory.IDENTITY, "e.g., Jr., III")
                )
            ),
            PersonalDataMultiTemplate(
                name = "Government ID",
                description = "ID type, number, issuing authority, expiry",
                category = DataCategory.IDENTITY,
                fields = listOf(
                    PersonalDataTemplateField("ID Type", "identity.gov_id.type", DataCategory.IDENTITY, "e.g., Passport, Driver License"),
                    PersonalDataTemplateField("Number", "identity.gov_id.number", DataCategory.IDENTITY, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Issuing Authority", "identity.gov_id.issuing_authority", DataCategory.IDENTITY, "e.g., State of California"),
                    PersonalDataTemplateField("Expiry Date", "identity.gov_id.expiry", DataCategory.IDENTITY, "", PersonalDataFieldInputHint.EXPIRY_DATE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Dependent",
                description = "Name, relationship, birth date, school, insurance",
                category = DataCategory.IDENTITY,
                fields = listOf(
                    PersonalDataTemplateField("Full Name", "identity.dependent.name", DataCategory.IDENTITY, "e.g., Jane Doe"),
                    PersonalDataTemplateField("Relationship", "identity.dependent.relationship", DataCategory.IDENTITY, "e.g., Child, Parent"),
                    PersonalDataTemplateField("Date of Birth", "identity.dependent.dob", DataCategory.IDENTITY, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("SSN (last 4)", "identity.dependent.ssn_last4", DataCategory.IDENTITY, "e.g., 1234", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("School", "identity.dependent.school", DataCategory.IDENTITY, "e.g., Lincoln Elementary"),
                    PersonalDataTemplateField("Medical Insurance ID", "identity.dependent.insurance_id", DataCategory.IDENTITY, "")
                )
            ),
            PersonalDataMultiTemplate(
                name = "Social Media Profile",
                description = "Platform, username, URL, email",
                category = DataCategory.CONTACT,
                fields = listOf(
                    PersonalDataTemplateField("Platform", "contact.social.platform", DataCategory.CONTACT, "e.g., Instagram, Twitter"),
                    PersonalDataTemplateField("Username / Handle", "contact.social.username", DataCategory.CONTACT, "e.g., @johndoe"),
                    PersonalDataTemplateField("Profile URL", "contact.social.url", DataCategory.CONTACT, "e.g., https://instagram.com/johndoe"),
                    PersonalDataTemplateField("Associated Email", "contact.social.email", DataCategory.CONTACT, "", PersonalDataFieldInputHint.EMAIL)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Employment Record",
                description = "Company, title, department, dates, contact",
                category = DataCategory.PROFESSIONAL,
                fields = listOf(
                    PersonalDataTemplateField("Company", "professional.employment.company", DataCategory.PROFESSIONAL, "e.g., Acme Corp"),
                    PersonalDataTemplateField("Job Title", "professional.employment.title", DataCategory.PROFESSIONAL, "e.g., Software Engineer"),
                    PersonalDataTemplateField("Department", "professional.employment.department", DataCategory.PROFESSIONAL, "e.g., Engineering"),
                    PersonalDataTemplateField("Start Date", "professional.employment.start_date", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("End Date", "professional.employment.end_date", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Work Phone", "professional.employment.phone", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.PHONE),
                    PersonalDataTemplateField("Work Email", "professional.employment.email", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.EMAIL)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Professional License",
                description = "License type, number, authority, dates",
                category = DataCategory.PROFESSIONAL,
                fields = listOf(
                    PersonalDataTemplateField("License Type", "professional.license.type", DataCategory.PROFESSIONAL, "e.g., CPA, RN, PE"),
                    PersonalDataTemplateField("License Number", "professional.license.number", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Issuing Authority", "professional.license.authority", DataCategory.PROFESSIONAL, "e.g., State Board of Nursing"),
                    PersonalDataTemplateField("Issue Date", "professional.license.issue_date", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Expiry Date", "professional.license.expiry_date", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("State / Province", "professional.license.state", DataCategory.PROFESSIONAL, "", PersonalDataFieldInputHint.STATE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Degree",
                description = "Institution, degree, major, graduation date",
                category = DataCategory.EDUCATION,
                fields = listOf(
                    PersonalDataTemplateField("Institution Name", "education.degree.institution", DataCategory.EDUCATION, "e.g., MIT"),
                    PersonalDataTemplateField("Degree Type", "education.degree.type", DataCategory.EDUCATION, "e.g., Bachelor of Science"),
                    PersonalDataTemplateField("Major", "education.degree.major", DataCategory.EDUCATION, "e.g., Computer Science"),
                    PersonalDataTemplateField("Minor", "education.degree.minor", DataCategory.EDUCATION, "e.g., Mathematics"),
                    PersonalDataTemplateField("Graduation Date", "education.degree.graduation_date", DataCategory.EDUCATION, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Student ID", "education.degree.student_id", DataCategory.EDUCATION, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("GPA", "education.degree.gpa", DataCategory.EDUCATION, "e.g., 3.8", PersonalDataFieldInputHint.NUMBER)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Vehicle Record",
                description = "Year, make, model, VIN, plate, registration",
                category = DataCategory.VEHICLE,
                fields = listOf(
                    PersonalDataTemplateField("Year", "vehicle.auto.year", DataCategory.VEHICLE, "e.g., 2023", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Make", "vehicle.auto.make", DataCategory.VEHICLE, "e.g., Toyota"),
                    PersonalDataTemplateField("Model", "vehicle.auto.model", DataCategory.VEHICLE, "e.g., Camry"),
                    PersonalDataTemplateField("Color", "vehicle.auto.color", DataCategory.VEHICLE, "e.g., Silver"),
                    PersonalDataTemplateField("VIN", "vehicle.auto.vin", DataCategory.VEHICLE, "17-character VIN"),
                    PersonalDataTemplateField("License Plate", "vehicle.auto.plate", DataCategory.VEHICLE, "e.g., ABC 1234"),
                    PersonalDataTemplateField("State", "vehicle.auto.state", DataCategory.VEHICLE, "", PersonalDataFieldInputHint.STATE),
                    PersonalDataTemplateField("Registration Expiry", "vehicle.auto.reg_expiry", DataCategory.VEHICLE, "", PersonalDataFieldInputHint.DATE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Visa",
                description = "Country, type, number, dates",
                category = DataCategory.TRAVEL,
                fields = listOf(
                    PersonalDataTemplateField("Country", "travel.visa.country", DataCategory.TRAVEL, "", PersonalDataFieldInputHint.COUNTRY),
                    PersonalDataTemplateField("Visa Type", "travel.visa.type", DataCategory.TRAVEL, "e.g., B-1, F-1, Tourist"),
                    PersonalDataTemplateField("Visa Number", "travel.visa.number", DataCategory.TRAVEL, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Issue Date", "travel.visa.issue_date", DataCategory.TRAVEL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Expiry Date", "travel.visa.expiry_date", DataCategory.TRAVEL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Entries", "travel.visa.entries", DataCategory.TRAVEL, "e.g., Single, Multiple")
                )
            ),
            PersonalDataMultiTemplate(
                name = "Loyalty Program",
                description = "Program, provider, member number, tier",
                category = DataCategory.TRAVEL,
                fields = listOf(
                    PersonalDataTemplateField("Program Name", "travel.loyalty.program", DataCategory.TRAVEL, "e.g., Delta SkyMiles"),
                    PersonalDataTemplateField("Provider", "travel.loyalty.provider", DataCategory.TRAVEL, "e.g., Airline, Hotel, Rental Car"),
                    PersonalDataTemplateField("Member Number", "travel.loyalty.number", DataCategory.TRAVEL, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Tier / Status", "travel.loyalty.tier", DataCategory.TRAVEL, "e.g., Gold, Platinum"),
                    PersonalDataTemplateField("Expiry Date", "travel.loyalty.expiry_date", DataCategory.TRAVEL, "", PersonalDataFieldInputHint.DATE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Beneficiary",
                description = "Name, relationship, contact, share",
                category = DataCategory.LEGAL,
                fields = listOf(
                    PersonalDataTemplateField("Full Name", "legal.beneficiary.name", DataCategory.LEGAL, "e.g., Jane Doe"),
                    PersonalDataTemplateField("Relationship", "legal.beneficiary.relationship", DataCategory.LEGAL, "e.g., Spouse, Child"),
                    PersonalDataTemplateField("Date of Birth", "legal.beneficiary.dob", DataCategory.LEGAL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Phone", "legal.beneficiary.phone", DataCategory.LEGAL, "", PersonalDataFieldInputHint.PHONE),
                    PersonalDataTemplateField("Email", "legal.beneficiary.email", DataCategory.LEGAL, "", PersonalDataFieldInputHint.EMAIL),
                    PersonalDataTemplateField("Percentage / Share", "legal.beneficiary.share", DataCategory.LEGAL, "e.g., 50%", PersonalDataFieldInputHint.NUMBER)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Prescription",
                description = "Medication, dosage, doctor, pharmacy",
                category = DataCategory.MEDICAL,
                fields = listOf(
                    PersonalDataTemplateField("Medication Name", "medical.prescription.medication", DataCategory.MEDICAL, "e.g., Lisinopril"),
                    PersonalDataTemplateField("Dosage", "medical.prescription.dosage", DataCategory.MEDICAL, "e.g., 10mg"),
                    PersonalDataTemplateField("Frequency", "medical.prescription.frequency", DataCategory.MEDICAL, "e.g., Once daily"),
                    PersonalDataTemplateField("Prescribing Doctor", "medical.prescription.doctor", DataCategory.MEDICAL, "e.g., Dr. Smith"),
                    PersonalDataTemplateField("Pharmacy", "medical.prescription.pharmacy", DataCategory.MEDICAL, "e.g., CVS Pharmacy"),
                    PersonalDataTemplateField("Refills Remaining", "medical.prescription.refills", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Expiry Date", "medical.prescription.expiry", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.DATE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Insurance Policy",
                description = "Provider, plan, policy, member, dates",
                category = DataCategory.MEDICAL,
                fields = listOf(
                    PersonalDataTemplateField("Provider", "medical.insurance.provider", DataCategory.MEDICAL, "e.g., Blue Cross"),
                    PersonalDataTemplateField("Plan Name", "medical.insurance.plan", DataCategory.MEDICAL, "e.g., PPO Gold"),
                    PersonalDataTemplateField("Policy Number", "medical.insurance.policy", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Group Number", "medical.insurance.group", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("Member ID", "medical.insurance.member_id", DataCategory.MEDICAL, ""),
                    PersonalDataTemplateField("Subscriber Name", "medical.insurance.subscriber", DataCategory.MEDICAL, ""),
                    PersonalDataTemplateField("Effective Date", "medical.insurance.effective_date", DataCategory.MEDICAL, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Copay Amount", "medical.insurance.copay", DataCategory.MEDICAL, "e.g., $25", PersonalDataFieldInputHint.NUMBER)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Organization Membership",
                description = "Organization, type, member ID, dates",
                category = DataCategory.MEMBERSHIP,
                fields = listOf(
                    PersonalDataTemplateField("Organization Name", "membership.org.name", DataCategory.MEMBERSHIP, "e.g., YMCA"),
                    PersonalDataTemplateField("Membership Type / Tier", "membership.org.tier", DataCategory.MEMBERSHIP, "e.g., Gold, Family"),
                    PersonalDataTemplateField("Member ID", "membership.org.member_id", DataCategory.MEMBERSHIP, ""),
                    PersonalDataTemplateField("Join Date", "membership.org.join_date", DataCategory.MEMBERSHIP, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Expiry Date", "membership.org.expiry_date", DataCategory.MEMBERSHIP, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Contact Phone", "membership.org.phone", DataCategory.MEMBERSHIP, "", PersonalDataFieldInputHint.PHONE)
                )
            ),
            PersonalDataMultiTemplate(
                name = "Property Record",
                description = "Address, type, mortgage, HOA",
                category = DataCategory.PROPERTY,
                fields = listOf(
                    PersonalDataTemplateField("Property Address", "property.owned.address", DataCategory.PROPERTY, "e.g., 123 Oak Lane"),
                    PersonalDataTemplateField("Property Type", "property.owned.type", DataCategory.PROPERTY, "e.g., House, Condo"),
                    PersonalDataTemplateField("Purchase Date", "property.owned.purchase_date", DataCategory.PROPERTY, "", PersonalDataFieldInputHint.DATE),
                    PersonalDataTemplateField("Mortgage Lender", "property.owned.lender", DataCategory.PROPERTY, "e.g., Wells Fargo"),
                    PersonalDataTemplateField("Mortgage Account", "property.owned.mortgage_account", DataCategory.PROPERTY, "", PersonalDataFieldInputHint.NUMBER),
                    PersonalDataTemplateField("HOA Name", "property.owned.hoa_name", DataCategory.PROPERTY, ""),
                    PersonalDataTemplateField("HOA Account", "property.owned.hoa_account", DataCategory.PROPERTY, "")
                )
            )
        )
    }
}

/**
 * State for the multi-field template form dialog.
 */
data class PersonalDataTemplateFormState(
    val template: PersonalDataMultiTemplate,
    val fieldValues: Map<Int, String> = emptyMap(),
    val isSaving: Boolean = false
) {
    fun getValue(fieldIndex: Int): String = fieldValues[fieldIndex] ?: ""
    fun hasAnyValue(): Boolean = fieldValues.values.any { it.isNotBlank() }
}

/**
 * State for the personal data list screen.
 */
sealed class PersonalDataState {
    object Loading : PersonalDataState()
    data class Loaded(
        val items: List<PersonalDataItem>,
        val searchQuery: String = ""
    ) : PersonalDataState()
    data class Error(val message: String) : PersonalDataState()
    object Empty : PersonalDataState()
}

/**
 * Grouped personal data by type for display.
 * @deprecated Use GroupedByCategory instead
 */
data class GroupedPersonalData(
    val publicData: List<PersonalDataItem>,
    val privateData: List<PersonalDataItem>,
    val keys: List<PersonalDataItem>,
    val minorSecrets: List<PersonalDataItem>
)

/**
 * Grouped personal data by category for display.
 * Each category section shows all data items regardless of type,
 * with a toggle to include/exclude from public profile.
 */
data class GroupedByCategory(
    val categories: Map<DataCategory, List<PersonalDataItem>>
) {
    companion object {
        fun fromItems(items: List<PersonalDataItem>): GroupedByCategory {
            val grouped = items.groupBy { it.category ?: DataCategory.OTHER }
            val orderedCategories = linkedMapOf<DataCategory, List<PersonalDataItem>>()
            listOf(
                DataCategory.IDENTITY,
                DataCategory.CONTACT,
                DataCategory.ADDRESS,
                DataCategory.FINANCIAL,
                DataCategory.MEDICAL,
                DataCategory.PROFESSIONAL,
                DataCategory.EDUCATION,
                DataCategory.VEHICLE,
                DataCategory.LEGAL,
                DataCategory.DIGITAL,
                DataCategory.TRAVEL,
                DataCategory.MEMBERSHIP,
                DataCategory.PROPERTY,
                DataCategory.OTHER
            ).forEach { category ->
                grouped[category]?.let { categoryItems ->
                    orderedCategories[category] = categoryItems.sortedWith(
                        compareBy({ it.sortOrder }, { it.name })
                    )
                }
            }
            return GroupedByCategory(orderedCategories)
        }
    }
}

/**
 * Effects emitted by the personal data view model.
 */
sealed class PersonalDataEffect {
    data class ShowSuccess(val message: String) : PersonalDataEffect()
    data class ShowError(val message: String) : PersonalDataEffect()
    data class NavigateToEdit(val itemId: String?) : PersonalDataEffect()
    object NavigateBack : PersonalDataEffect()
}

/**
 * Events for the personal data screen.
 */
sealed class PersonalDataEvent {
    data class SearchQueryChanged(val query: String) : PersonalDataEvent()
    data class ItemClicked(val itemId: String) : PersonalDataEvent()
    object AddItem : PersonalDataEvent()
    data class DeleteItem(val itemId: String) : PersonalDataEvent()
    data class TogglePublicProfile(val itemId: String) : PersonalDataEvent()
    data class MoveItemUp(val itemId: String) : PersonalDataEvent()
    data class MoveItemDown(val itemId: String) : PersonalDataEvent()
    object Refresh : PersonalDataEvent()
}

/**
 * State for add/edit dialog.
 */
data class EditDataItemState(
    val id: String? = null,
    val name: String = "",
    val value: String = "",
    val type: DataType = DataType.PRIVATE,
    val fieldType: FieldType = FieldType.TEXT,
    val category: DataCategory? = null,
    val originalCategory: DataCategory? = null,  // Track original category for detecting changes
    val isInPublicProfile: Boolean = false,
    val sortOrder: Int = 0,
    val isEditing: Boolean = false,
    val isSaving: Boolean = false,
    val nameError: String? = null,
    val valueError: String? = null
)

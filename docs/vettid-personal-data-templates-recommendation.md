# VettID Personal Data Templates — Comprehensive Recommendation

## 1. Current State Summary

After reviewing the `vettid-android` codebase, here is the inventory of what currently exists.

### 1.1 Data Categories (`DataCategory`)

| Category | Icon | Status |
|----------|------|--------|
| Identity | person | ✅ Exists |
| Contact | phone | ✅ Exists |
| Address | location_on | ✅ Exists |
| Financial | account_balance | ✅ Exists |
| Medical | medical_services | ✅ Exists |
| Other | category | ✅ Exists |

### 1.2 Single-Field Personal Data Templates (28 total)

**Identity (8):** Date of Birth, SSN, National ID, Passport Number, Driver License, Place of Birth, Nationality, Gender

**Contact (7):** Mobile Phone, Home Phone, Work Phone, Personal Email, Work Email, Website, LinkedIn

**Address (7):** Home Address, Mailing Address, Work Address, City, State/Province, Postal Code, Country

**Financial (5):** Bank Name, Bank Account, Routing Number, IBAN, Tax ID

**Medical (8):** Blood Type, Allergies, Medical Conditions, Emergency Contact, Emergency Phone, Insurance Provider, Insurance ID, Primary Physician

### 1.3 Multi-Field Personal Data Templates (6 total)

Home Address, Business Address, Family Member, Emergency Contact, Full Name, Government ID

### 1.4 Secret Categories (`SecretCategory`, 14 total)

Identity, Cryptocurrency, Bank Account, Credit Card, Insurance, Driver's License, Passport, SSN, API Key, Password, WiFi, Certificate, Note, Other

### 1.5 Secret Templates (8 total)

Driver's License, Passport, Bank Account, Credit Card, Cryptocurrency Wallet, Insurance, Social Security, WiFi Network

---

## 2. Gap Analysis

### 2.1 Missing Data Categories

The current six `DataCategory` values cover basic personal data well, but several real-world domains are absent. The following new categories would make VettID comprehensive enough for daily life:

| Proposed Category | Icon | Rationale |
|-------------------|------|-----------|
| **Professional** | work | Employment, professional licenses, certifications |
| **Education** | school | Degrees, student IDs, transcripts |
| **Vehicle** | directions_car | Cars, registration, VIN — used for insurance, DMV |
| **Legal** | gavel | Power of attorney, beneficiaries, legal docs |
| **Digital** | language | Social handles, gaming accounts, digital wallets |
| **Travel** | flight_takeoff | Loyalty programs, known traveler, visa info |
| **Membership** | card_membership | Gym, clubs, professional orgs, library cards |
| **Property** | home | Real estate, mortgage, HOA, rental leases |

### 2.2 Missing Single-Field Templates

The current 28 single-field templates are heavily weighted toward Identity and Medical. Many everyday fields are missing.

### 2.3 Missing Multi-Field Templates

Only 6 multi-field templates exist. Many real-world data types naturally have multiple related fields (e.g., a vehicle has make, model, year, VIN, plate — all connected).

### 2.4 Missing Secret Templates

The 8 existing secret templates cover financial basics but miss common credential types that people actually need to store securely.

---

## 3. Recommended Additions

### 3.1 New Data Categories

Add to the `DataCategory` enum:

```kotlin
enum class DataCategory(val displayName: String, val iconName: String) {
    // Existing
    IDENTITY("Identity", "person"),
    CONTACT("Contact", "phone"),
    ADDRESS("Address", "location_on"),
    FINANCIAL("Financial", "account_balance"),
    MEDICAL("Medical", "medical_services"),

    // New
    PROFESSIONAL("Professional", "work"),
    EDUCATION("Education", "school"),
    VEHICLE("Vehicle", "directions_car"),
    LEGAL("Legal", "gavel"),
    DIGITAL("Digital", "language"),
    TRAVEL("Travel", "flight_takeoff"),
    MEMBERSHIP("Membership", "card_membership"),
    PROPERTY("Property", "home"),

    // Keep last
    OTHER("Other", "category")
}
```

### 3.2 New Single-Field Templates

#### Professional (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Employer | TEXT | Current employer name |
| Job Title | TEXT | Current position/role |
| Work ID / Badge Number | TEXT | Employee identification |
| Department | TEXT | Department or division |
| Manager Name | TEXT | Direct supervisor name |
| Manager Email | EMAIL | Supervisor's email |
| Start Date | DATE | Employment start date |
| Professional License | TEXT | License type and number |
| License Expiration | DATE | License expiry date |
| LinkedIn | URL | Professional profile URL |

#### Education (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| School Name | TEXT | Institution name |
| Degree | TEXT | Degree earned (e.g., BS Computer Science) |
| Graduation Date | DATE | Date of graduation |
| Student ID | TEXT | Student identification number |
| GPA | TEXT | Grade point average |
| Major | TEXT | Primary field of study |
| Minor | TEXT | Secondary field of study |

#### Vehicle (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Vehicle Make | TEXT | Manufacturer (e.g., Toyota) |
| Vehicle Model | TEXT | Model name (e.g., Camry) |
| Vehicle Year | TEXT | Model year |
| VIN | TEXT | Vehicle Identification Number |
| License Plate | TEXT | Plate number and state |
| Vehicle Color | TEXT | Exterior color |
| Registration Expiry | DATE | Registration expiration date |

#### Legal (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Attorney Name | TEXT | Primary attorney |
| Attorney Phone | PHONE | Attorney's phone |
| Attorney Email | EMAIL | Attorney's email |
| Power of Attorney | TEXT | POA holder name |
| Beneficiary Name | TEXT | Primary beneficiary |
| Beneficiary Relationship | TEXT | Relationship to beneficiary |
| Will Location | NOTE | Where will/trust documents are stored |

#### Digital (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| GitHub Username | TEXT | GitHub handle |
| Twitter/X Handle | TEXT | Social media handle |
| Discord Username | TEXT | Discord identifier |
| Apple ID Email | EMAIL | Apple account email |
| Google Account | EMAIL | Google/Gmail account |
| Microsoft Account | EMAIL | Microsoft account email |
| Personal Domain | URL | Owned domain name |
| Recovery Email | EMAIL | Account recovery email |

#### Travel (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Known Traveler Number | TEXT | TSA PreCheck / Global Entry |
| Frequent Flyer Number | TEXT | Airline loyalty ID |
| Airline Preference | TEXT | Preferred airline |
| Hotel Loyalty Number | TEXT | Hotel rewards ID |
| Rental Car Loyalty | TEXT | Car rental rewards ID |
| Visa Number | TEXT | Travel visa number |
| Visa Expiry | DATE | Visa expiration date |
| Visa Country | TEXT | Country that issued visa |

#### Membership (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Membership Name | TEXT | Organization or program name |
| Member ID | TEXT | Membership number |
| Membership Type | TEXT | Level/tier (e.g., Gold, Premium) |
| Expiration Date | DATE | Membership expiry |
| Library Card Number | TEXT | Library card ID |

#### Property (new category)

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Property Address | NOTE | Full property address |
| Property Type | TEXT | House, Condo, Apartment, etc. |
| Mortgage Lender | TEXT | Mortgage company name |
| Mortgage Account | TEXT | Mortgage account number |
| HOA Name | TEXT | HOA organization name |
| HOA Account | TEXT | HOA account number |
| Landlord Name | TEXT | Landlord or property manager |
| Landlord Phone | PHONE | Landlord phone |
| Lease End Date | DATE | Lease expiration |

#### Additional Identity Templates

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Preferred Name / Nickname | TEXT | Name you go by |
| Maiden Name | TEXT | Birth surname if changed |
| Marital Status | TEXT | Single, Married, etc. |
| Spouse Name | TEXT | Spouse's full name |
| Number of Dependents | NUMBER | Dependent count (tax-relevant) |
| Eye Color | TEXT | Eye color (for ID matching) |
| Height | TEXT | Height (for ID matching) |
| Weight | TEXT | Weight (for ID matching) |

#### Additional Contact Templates

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Fax Number | PHONE | Fax number (legal/medical) |
| WhatsApp Number | PHONE | WhatsApp contact |
| Signal Number | PHONE | Signal messenger number |
| Telegram Handle | TEXT | Telegram username |
| Skype ID | TEXT | Skype username |

#### Additional Medical Templates

| Template Name | FieldType | Description |
|---------------|-----------|-------------|
| Medications | NOTE | Current medications and dosages |
| Pharmacy Name | TEXT | Preferred pharmacy |
| Pharmacy Phone | PHONE | Pharmacy phone number |
| Organ Donor Status | TEXT | Yes/No/Registered |
| Advance Directive Location | TEXT | Where directive is stored |
| Dentist Name | TEXT | Dentist's name |
| Dentist Phone | PHONE | Dentist's phone |
| Vision Prescription | NOTE | Current lens prescription |
| Medical Power of Attorney | TEXT | Healthcare proxy name |
| Health Insurance Group | TEXT | Group number |
| Dental Insurance Provider | TEXT | Dental insurance company |
| Dental Insurance ID | TEXT | Dental member ID |
| Vision Insurance Provider | TEXT | Vision insurance company |
| Vision Insurance ID | TEXT | Vision member ID |

---

### 3.3 New Multi-Field Templates

Add to `PersonalDataMultiTemplate.all`:

#### Professional — Employment Record
```
Fields: Company, Job Title, Department, Start Date, End Date, Work Phone, Work Email
Namespace: professional.employment.*
```

#### Professional — Professional License
```
Fields: License Type, License Number, Issuing Authority, Issue Date, Expiry Date, State/Province
Namespace: professional.license.*
```

#### Education — Degree
```
Fields: Institution Name, Degree Type, Major, Minor, Graduation Date, Student ID, GPA
Namespace: education.degree.*
```

#### Vehicle — Vehicle Record
```
Fields: Year, Make, Model, Color, VIN, License Plate, State, Registration Expiry
Namespace: vehicle.auto.*
```

#### Travel — Visa
```
Fields: Country, Visa Type, Visa Number, Issue Date, Expiry Date, Entries (Single/Multiple)
Namespace: travel.visa.*
```

#### Travel — Loyalty Program
```
Fields: Program Name, Provider (Airline/Hotel/Rental), Member Number, Tier/Status, Expiry Date
Namespace: travel.loyalty.*
```

#### Property — Property Record
```
Fields: Property Address, Property Type, Purchase Date, Mortgage Lender, Mortgage Account, HOA Name, HOA Account
Namespace: property.owned.*
```

#### Legal — Beneficiary
```
Fields: Full Name, Relationship, Date of Birth, Phone, Email, Percentage/Share
Namespace: legal.beneficiary.*
```

#### Medical — Prescription
```
Fields: Medication Name, Dosage, Frequency, Prescribing Doctor, Pharmacy, Refills Remaining, Expiry Date
Namespace: medical.prescription.*
```

#### Medical — Insurance Policy
```
Fields: Provider, Plan Name, Policy Number, Group Number, Member ID, Subscriber Name, Effective Date, Copay Amount
Namespace: medical.insurance.*
```

#### Membership — Organization
```
Fields: Organization Name, Membership Type/Tier, Member ID, Join Date, Expiry Date, Contact Phone
Namespace: membership.org.*
```

#### Contact — Social Media Profile
```
Fields: Platform, Username/Handle, Profile URL, Associated Email
Namespace: contact.social.*
```

#### Identity — Dependent
```
Fields: Full Name, Relationship, Date of Birth, SSN (last 4), School, Medical Insurance ID
Namespace: identity.dependent.*
```

---

### 3.4 New Secret Templates

Add to `SecretTemplate.all`:

#### Login Credential
```
Category: PASSWORD
Fields: Website/Service, Username, Password, 2FA Method, Recovery Codes
Icon: login
```

#### Debit Card
```
Category: CREDIT_CARD (or new DEBIT_CARD)
Fields: Cardholder Name, Card Number, Expiration, PIN, Bank Name
Icon: credit_card
```

#### Software License
```
Category: OTHER (or new LICENSE)
Fields: Product Name, License Key, Registered Email, Expiry Date, Seats/Devices
Icon: key
```

#### SSH Key
```
Category: CERTIFICATE
Fields: Label, Public Key, Private Key, Passphrase, Associated Host
Icon: terminal
```

#### PGP/GPG Key
```
Category: CERTIFICATE
Fields: Email, Key ID, Fingerprint, Public Key, Private Key, Passphrase
Icon: enhanced_encryption
```

#### VPN Configuration
```
Category: PASSWORD
Fields: Provider, Server Address, Username, Password/Key, Protocol (OpenVPN/WireGuard)
Icon: vpn_key
```

#### TOTP Secret (Authenticator)
```
Category: OTHER (or new TOTP)
Fields: Service Name, Account/Email, Secret Key, Algorithm (SHA1/SHA256), Digits (6/8)
Icon: timer
```

#### API Credential
```
Category: API_KEY
Fields: Service Name, API Key, API Secret, Base URL, Rate Limit Notes
Icon: api
```

#### Medical Prescription
```
Category: NOTE
Fields: Medication Name, Dosage, Frequency, Prescribing Doctor, Pharmacy, RX Number
Icon: medication
```

#### Vehicle Registration
```
Category: OTHER (or new VEHICLE)
Fields: Plate Number, State, VIN, Registration Number, Expiry Date
Icon: directions_car
```

#### Loyalty/Rewards Card
```
Category: OTHER (or new LOYALTY)
Fields: Program Name, Member Number, Tier/Status, PIN (if any)
Icon: card_giftcard
```

#### Tax Filing Reference
```
Category: OTHER (or new TAX)
Fields: Tax Year, Filing Status, AGI, Refund/Owed, Preparer, Confirmation Number
Icon: receipt_long
```

#### Digital Certificate (X.509)
```
Category: CERTIFICATE
Fields: Subject/Common Name, Issuer, Serial Number, Valid From, Valid To, PEM Content
Icon: verified_user
```

---

### 3.5 New Secret Categories

Add to `SecretCategory`:

```kotlin
enum class SecretCategory(val displayName: String, val iconName: String) {
    // Existing...

    // New
    LOGIN("Login Credential", "login"),
    TOTP("Authenticator", "timer"),
    SOFTWARE_LICENSE("Software License", "key"),
    VEHICLE("Vehicle", "directions_car"),
    LOYALTY("Loyalty/Rewards", "card_giftcard"),
    TAX("Tax", "receipt_long"),
    VPN("VPN", "vpn_key"),
    SSH("SSH Key", "terminal"),

    // Existing
    OTHER("Other", "category")
}
```

---

## 4. Summary of Changes

| Component | Current Count | Proposed Additions | New Total |
|-----------|:------------:|:------------------:|:---------:|
| Data Categories | 6 | +8 | **14** |
| Single-Field Templates | 28 | +68 | **96** |
| Multi-Field Templates | 6 | +13 | **19** |
| Secret Categories | 14 | +8 | **22** |
| Secret Templates | 8 | +13 | **21** |

---

## 5. Implementation Notes

### 5.1 Namespace Convention

The existing multi-field templates use a hierarchical namespace pattern: `category.subcategory.field`. The new templates follow the same convention for consistency and interoperability across the personal information catalog and secrets metadata catalog.

### 5.2 Phased Rollout Suggestion

**Phase 1 — High Value / Common Use:** Login Credential, Debit Card, TOTP Secret, Professional (Employment), Vehicle Record, Loyalty/Rewards Card, Dependent. These are things people use daily.

**Phase 2 — Comprehensive Life Management:** Education, Property, Legal (Beneficiary), Travel (Visa, Loyalty), Medical (Prescription, Insurance Policy), Software License.

**Phase 3 — Power User / Technical:** SSH Key, PGP Key, VPN, API Credential, Digital Certificate, Tax Filing.

### 5.3 Data Tier Alignment

All new templates align with the three-tier data model from the "Three Locks" architecture:

- **Personal Data** (describes you): Professional, Education, Vehicle metadata, Travel preferences, Membership info, Property addresses. Governed by sharing controls per connection.
- **Secrets** (acts on your behalf): Login Credentials, Debit/Credit Cards, Software Licenses, Loyalty PINs. Gated — connections must request access each time.
- **Critical Secrets** (irreversible if exposed): SSH private keys, PGP private keys, TOTP secrets, seed phrases. Double-PIN, credential-embedded, never cached.

### 5.4 Catalog Interoperability

New personal data templates automatically populate the **personal information catalog** visible to connections. New secret templates automatically generate **secrets metadata catalog** entries (e.g., "Visa ending in 4242", "AWS API Key: production") — exposing type and display name but never the actual credential values.

### 5.5 Field Input Hints

The new `PersonalDataFieldInputHint` enum is sufficient for all proposed templates. No new input hint types are needed.

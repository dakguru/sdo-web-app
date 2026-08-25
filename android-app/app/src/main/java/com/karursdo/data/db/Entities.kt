package com.karursdo.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

/**
 * Departmental (DS) and GDS staff share one payroll shape. The indexed columns
 * below are the ones the UI filters/sorts on; [payloadJson] preserves the FULL
 * imported row as an ordered list of (column, value) pairs so the pay-breakdown
 * sections can slice earnings/deductions positionally, exactly like the web app.
 */
@Serializable
@Entity(
    tableName = "employees",
    primaryKeys = ["type", "employeeId"],
    indices = [Index("officeId"), Index("name")]
)
data class EmployeeEntity(
    val type: String,            // "DS" | "GDS"
    val employeeId: String,
    val payrollId: String?,
    val ddoOfficeId: String?,
    val name: String,
    val gender: String?,
    val level: String?,
    val postId: String?,
    val designation: String?,    // Post_desc
    val estKey: String?,
    val estDescription: String?,
    val officeId: String,
    val officeName: String?,     // Office_desc
    val pan: String?,
    val dateOfBirth: String?,
    val dateOfJoin: String?,
    val bankAccNo: String?,
    val ifsc: String?,
    val bankType: String?,
    val status: String?,
    val totalEarnings: Double?,
    val totalDeductions: Double?,
    val totalThirdparty: Double?,
    val netPayable: Double?,
    val payloadJson: String
)

@Serializable
@Entity(tableName = "outsiders", indices = [Index("officeId"), Index("fullName")])
data class OutsiderEntity(
    @PrimaryKey val resourceId: String,
    val fullName: String,
    val firstName: String?,
    val middleName: String?,
    val lastName: String?,
    val fatherName: String?,
    val dateOfBirth: String?,
    val community: String?,
    val gender: String?,
    val address1: String?,
    val address2: String?,
    val address3: String?,
    val address4: String?,
    val pinCode: String?,
    val email: String?,
    val mobile: String?,
    val aadhaar: String?,
    val pan: String?,
    val education: String?,
    val approverPostId: String?,
    val loginRequired: String?,
    val makerId: String?,
    val remarks: String?,
    val officeId: String?,
    val officeName: String?,
    val subDivisionId: String?,
    val divisionId: String?,
    val divisionName: String?,
    val bankAccNo: String?,
    val ifsc: String?,
    val bankType: String?,
    val resourceStatus: String?,
    val payloadJson: String
)

/** Employee_id -> phone map produced by matching the mobile roster (mobiles.json equivalent). */
@Serializable
@Entity(tableName = "mobile_map")
data class MobileMapEntity(
    @PrimaryKey val employeeId: String,
    val phone: String
)

/**
 * A user's manual edit to a staff mobile number, synced to every device (last-write-wins).
 * The edit is applied on top of the imported directory: for an EMPLOYEE it drives `mobile_map`,
 * for an OUTSIDER it drives the outsider row's `mobile`. Keyed by (targetType, targetId).
 */
@Entity(tableName = "phone_edits", primaryKeys = ["targetType", "targetId"])
data class PhoneEditEntity(
    val targetType: String,   // "EMPLOYEE" | "OUTSIDER"
    val targetId: String,     // employeeId or resourceId
    val phone: String,        // edited number ("" = cleared)
    val updatedBy: String?,   // who edited (display name / username)
    val updatedAt: Long,      // epoch millis of the edit
    val syncState: String = "P"
)

/** Raw imported mobile-roster rows, kept verbatim so re-matching stays possible. */
@Entity(tableName = "mobile_roster")
data class MobileRosterEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serialNo: String?,
    val officeName: String?,
    val designation: String?,
    val staffName: String?,
    val phone: String?,
    val altStaff: String?
)

/** Authoritative office master (HRMS "Office Management" export). */
@Serializable
@Entity(tableName = "office_master", indices = [Index("officeName"), Index("subDivision")])
data class OfficeMasterEntity(
    @PrimaryKey val officeId: String,
    val officeName: String,
    val officeType: String?,     // BPO / SPO / SDO / HPO ...
    val officeClass: String?,
    val officeStatus: String?,
    val subDivision: String?,
    val subDivisionOfficeId: String?,
    val division: String?,
    val hoName: String?,
    val soName: String?,
    val region: String?,
    val circle: String?,
    val email: String?,
    val contact: String?,
    val pincode: String?,
    val address1: String?,
    val address2: String?,
    val address3: String?,
    val village: String?,
    val taluk: String?,
    val city: String?,
    val state: String?,
    val latitude: String?,
    val longitude: String?,
    val workingDays: String?,
    val workingHoursFrom: String?,
    val workingHoursTo: String?,
    val solId: String?,
    val csiFacilityId: String?,
    val pliId: String?,
    val gstn: String?,
    val paoCode: String?,
    val deliveryOffice: String?,
    val reportingOfficeId: String?,
    val headOfOfficeId: String?
)

/**
 * Temporary arrangements (Karur Sub Division only) for the three tracked categories:
 * "GDS Post by Outsrc Resource" (GDS), "Non Sanc Post by Outsrc Resource" (NONSANC_OUT)
 * and "Non Sanc Post by GDS Emp" (NONSANC_GDS). One row = one arrangement covering a post
 * at an office by a substitute person, for a date range.
 */
@Serializable
@Entity(tableName = "arrangements", indices = [Index("officeId"), Index("category")])
data class ArrangementEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,          // "GDS" | "NONSANC_OUT" | "NONSANC_GDS"
    val arrangementType: String?,  // full HRMS arrangement_type_desc
    val post: String?,             // new_designation (the post being covered)
    val officeName: String?,
    val officeId: String?,
    val personId: String?,         // resource_id / employee_id of the substitute
    val personName: String?,       // resource_name / employee_name of the substitute
    val substituteId: String?,     // retained for compatibility (= personId)
    val substituteName: String?,   // retained for compatibility (= personName)
    val fromDate: String?,         // dd-mm-yyyy
    val toDate: String?,
    val status: String?
)

/**
 * Mail Overseer beat office (from the MO Visit Register). Each office belongs to a
 * beat ("MO_I" | "MO_II"), links to the office master by [matchedOfficeId] when a
 * match is found, and keeps its visit history as a comma-separated list of ISO
 * dates (yyyy-MM-dd) in [visits], sorted ascending.
 */
@Entity(tableName = "mo_beat_offices", indices = [Index("beat")])
data class MoBeatOfficeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val beat: String,                 // "MO_I" | "MO_II"
    val serialNo: Int,
    val officeName: String,
    val matchedOfficeId: String?,     // office_master.officeId when resolved
    val visits: String,               // comma-separated ISO dates, ascending
    // Provenance of the last visit edit (synced across users, keyed by beat+serialNo).
    val updatedBy: String? = null,    // display name / username of the editor
    val updatedAt: Long? = null,      // epoch millis of the last edit
    val syncState: String? = null     // "P" pending push, else synced/baseline
)

/**
 * A Mail Overseer's tour programme: for a given beat (MO_I / MO_II) and calendar date,
 * what the overseer plans to do / which offices they intend to visit. One row per date
 * (a multi-date programme is stored as several rows). Synced across users last-write-wins.
 */
@Entity(tableName = "mo_programmes", indices = [Index("beat"), Index("date")])
data class MoProgrammeEntity(
    @PrimaryKey val id: String,       // UUID
    val beat: String,                 // "MO_I" | "MO_II"
    val date: String,                 // ISO yyyy-MM-dd
    val details: String,
    val author: String? = null,       // who entered it (display name / username)
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val syncState: String = "P"       // "P" pending push, "S" synced
)

/** Data-source freshness badges (DS / GS / OUT / TEL / OFFICES / ARR). */
@Entity(tableName = "import_meta")
data class ImportMetaEntity(
    @PrimaryKey val type: String,
    val fileName: String,
    val recordCount: Int,
    val updatedAt: String        // ISO date
)

// ---------------------------------------------------------------------------
// App-user data (created behind the login gate). These four tables hold the
// per-install user layer: starred records, personal notes, profile/preferences,
// and an activity trail. Every row carries a [syncState] so the Phase-3 Supabase
// sync engine can push PENDING rows and mark them SYNCED without another schema
// migration. SYNC_PENDING = "P", SYNC_DONE = "S", SYNC_DELETED = "D".
// ---------------------------------------------------------------------------

/** A starred employee / outsider / office, keyed by (kind, refId). */
@Entity(tableName = "user_favorites", primaryKeys = ["itemType", "itemId"])
data class FavoriteEntity(
    val itemType: String,        // "EMPLOYEE" | "OUTSIDER" | "OFFICE"
    val itemId: String,          // employeeId | resourceId | officeId
    val label: String,           // cached display name for the favourites list
    val createdAt: Long,         // epoch millis
    val syncState: String = "P"
)

/** A free-text note, optionally attached to a directory record. */
@Entity(tableName = "user_notes", indices = [Index("targetType"), Index("targetId")])
data class NoteEntity(
    @PrimaryKey val id: String,  // UUID
    val targetType: String,      // "EMPLOYEE" | "OUTSIDER" | "OFFICE" | "GENERAL"
    val targetId: String?,       // null for a general note
    val title: String?,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val authorName: String? = null,  // display name of the user who last edited the note
    val syncState: String = "P"
)

/** Profile + preferences as a simple key/value store (display_name, role, …). */
@Entity(tableName = "user_prefs")
data class UserPrefEntity(
    @PrimaryKey val key: String,
    val value: String,
    val updatedAt: Long,
    val syncState: String = "P"
)

/** An append-only activity/edit trail for the shared login. */
@Entity(tableName = "activity_log", indices = [Index("createdAt")])
data class ActivityEntity(
    @PrimaryKey val id: String,  // UUID
    val action: String,          // "LOGIN" | "VIEW" | "EDIT_PHONE" | "ADD_NOTE" | "FAVORITE" | "MO_VISIT" | ...
    val targetType: String?,
    val targetId: String?,
    val summary: String,
    val createdAt: Long,
    val syncState: String = "P"
)

/**
 * One group-chat message. Authored by the signed-in user; synced to Supabase
 * (table app_messages) so every user sees the shared conversation.
 */
@Entity(tableName = "chat_messages", indices = [Index("createdAt")])
data class ChatMessageEntity(
    @PrimaryKey val id: String,   // UUID
    val username: String,
    val displayName: String,
    val body: String,
    val createdAt: Long,          // epoch millis
    val updatedAt: Long = 0L,     // epoch millis of last edit/delete (0 → same as createdAt)
    val deleted: Boolean = false, // soft-deleted (hidden everywhere), still synced
    val syncState: String? = null // "P" pending push, else synced
)

/**
 * A login account. Passwords are stored only as a salted PBKDF2 hash — never plaintext.
 * The admin (role = "ADMIN") provisions accounts; ordinary users have role = "USER".
 * Accounts sync to Supabase (table app_users) so an account created on one device can
 * sign in on another.
 */
@Entity(tableName = "users")
data class UserAccountEntity(
    @PrimaryKey val username: String,
    val passwordHash: String,          // Base64(PBKDF2-HMAC-SHA256)
    val salt: String,                  // Base64 random salt
    val iterations: Int,
    val displayName: String,
    val role: String,                  // "ADMIN" | "USER"
    val active: Boolean = true,
    val mustChangePassword: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val syncState: String = "P"
)

/**
 * Per-user "read up to" watermark for the group chat. A message is considered seen by a
 * user when that user's [lastReadAt] >= the message's createdAt. Synced across users so
 * every device can render WhatsApp-style "Seen by …" receipts. Keyed by username.
 */
@Entity(tableName = "chat_reads")
data class ChatReadEntity(
    @PrimaryKey val username: String,
    val lastReadAt: Long,     // epoch millis of the newest message this user has seen
    val updatedAt: Long,      // epoch millis of this row's last change (for last-write-wins)
    val syncState: String = "P"
)

/**
 * WhatsApp-style emoji reaction: one row per (message, user). [emoji] is the current reaction, or
 * "" when the user has removed their reaction (kept as a tombstone so removals sync last-write-wins).
 * Keyed by (messageId, username); synced across users.
 */
@Entity(tableName = "chat_reactions", primaryKeys = ["messageId", "username"])
data class ChatReactionEntity(
    val messageId: String,
    val username: String,
    val emoji: String,        // "" = no reaction (removed)
    val updatedAt: Long,
    val syncState: String = "P"
)

/**
 * Presence heartbeat: when each user was last active in the app. Drives the chat's
 * Online / "last seen …" display. Synced across users, keyed by username.
 */
@Entity(tableName = "presence")
data class PresenceEntity(
    @PrimaryKey val username: String,
    val lastSeenAt: Long,     // epoch millis of the user's last heartbeat
    val updatedAt: Long,
    val syncState: String = "P"
)

/**
 * A user's edit to an office's working days/hours, synced to every device (last-write-wins).
 * Applied on top of the imported office master so a working-hours change made by any user
 * shows for everyone. Keyed by [officeId]. Re-applied after a fresh OFFICES import so it sticks.
 */
@Entity(tableName = "office_edits")
data class OfficeEditEntity(
    @PrimaryKey val officeId: String,
    val workingDays: String?,
    val workingHoursFrom: String?,
    val workingHoursTo: String?,
    val updatedBy: String?,     // who edited (display name / username)
    val updatedAt: Long,        // epoch millis of the edit
    val syncState: String = "P"
)

/**
 * An app-side staff overlay, synced to every device (last-write-wins), keyed by (type, employeeId):
 *   • [added] = true  → a staff member entered manually in the app (materialised into `employees`).
 *   • exit fields set  → the exit/retirement details recorded for an existing staff member.
 * Re-applied after a fresh DS/GDS import so manual additions and exit marks survive re-imports.
 */
@Entity(tableName = "staff_edits", primaryKeys = ["type", "employeeId"])
data class StaffEditEntity(
    val type: String,           // "DS" | "GDS"
    val employeeId: String,
    val added: Boolean,         // true = manually-added staff (not from HRMS import)
    val name: String?,
    val designation: String?,
    val officeId: String?,
    val officeName: String?,
    val gender: String?,
    val dateOfBirth: String?,
    val dateOfJoin: String?,
    val mobile: String?,
    val exitDate: String?,      // date the staff exited/retired (dd-mm-yyyy or free text)
    val exitReason: String?,    // retirement / transfer / resignation …
    val status: String?,        // effective status to reflect on the staff row
    val updatedBy: String?,
    val updatedAt: Long,
    val syncState: String = "P"
)

/**
 * A Karur Sub Division event / announcement shown on the dashboard banner. A dated event
 * counts down the days remaining; [important] pins it to the top. An entry with a blank
 * [date] is a standing announcement (important message) with no countdown. Authored by an
 * Admin/ASP/PA user and synced to every device (last-write-wins).
 */
@Entity(tableName = "events", indices = [Index("date")])
data class EventEntity(
    @PrimaryKey val id: String,   // UUID
    val date: String,             // ISO yyyy-MM-dd, or "" for a standing announcement
    val title: String,
    val important: Boolean = false,
    val author: String? = null,
    val createdAt: Long,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val syncState: String = "P"
)

/**
 * A whole-set snapshot of one imported dataset (DS / GDS / OUT / TEL / OFFICES / ARR),
 * so a monthly-data or arrangements upload on one device propagates to every user. The
 * payload is the parsed rows as JSON; on pull, a newer remote snapshot replaces the local
 * set. Keyed by [type]; last-write-wins by [uploadedAt].
 */
@Entity(tableName = "dataset_snapshots")
data class DatasetSnapshotEntity(
    @PrimaryKey val type: String,   // "DS" | "GDS" | "OUT" | "TEL" | "OFFICES" | "ARR"
    val payloadJson: String,        // JSON array of the parsed rows for this dataset
    val count: Int,
    val uploadedBy: String?,        // display name / username of the uploader
    val uploadedAt: Long,           // epoch millis of the upload
    val syncState: String = "P"
)

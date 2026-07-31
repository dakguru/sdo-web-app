package com.karursdo.data.ingest

import com.karursdo.data.db.ArrangementEntity
import com.karursdo.data.db.EmployeeEntity
import com.karursdo.data.db.MobileRosterEntity
import com.karursdo.data.db.OutsiderEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

object RecordMappers {

    private val json = Json { ignoreUnknownKeys = true }
    private val pairListSerializer =
        ListSerializer(ListSerializer(String.serializer()))

    /** Ordered (column,value) pairs -> JSON, preserving payroll column order. */
    fun payloadJson(rec: Record): String =
        json.encodeToString(pairListSerializer, rec.map { listOf(it.key, it.value) })

    fun decodePayload(payload: String): List<Pair<String, String>> =
        json.decodeFromString(pairListSerializer, payload).map { it[0] to it.getOrElse(1) { "" } }

    private fun Record.str(key: String): String? =
        entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun Record.num(key: String): Double? = str(key)?.toDoubleOrNull()

    fun toEmployee(type: ImportType, rec: Record): EmployeeEntity? {
        val id = rec.str("Employee_id") ?: return null
        return EmployeeEntity(
            type = if (type == ImportType.DS) "DS" else "GDS",
            employeeId = id,
            payrollId = rec.str("Payroll_id"),
            ddoOfficeId = rec.str("DDO_Office_ID"),
            name = rec.str("Employee_name") ?: "—",
            gender = rec.str("Gender"),
            level = rec.str("Level"),
            postId = rec.str("Post_id"),
            designation = rec.str("Post_desc"),
            estKey = rec.str("Est_key"),
            estDescription = rec.str("Est_description"),
            officeId = rec.str("Office_id") ?: "(unassigned)",
            officeName = rec.str("Office_desc"),
            pan = rec.str("Pancard_number"),
            dateOfBirth = rec.str("Date_of_birth"),
            dateOfJoin = rec.str("Date_of_join"),
            bankAccNo = rec.str("Bank_acc_no"),
            ifsc = rec.str("IFSC_code"),
            bankType = rec.str("Bank_type"),
            status = rec.str("Status"),
            totalEarnings = rec.num("Total_earnings"),
            totalDeductions = rec.num("Total_deductions"),
            totalThirdparty = rec.num("Total_thirdparty_deduction"),
            netPayable = rec.num("Net_payable"),
            payloadJson = payloadJson(rec)
        )
    }

    fun toOutsider(rec: Record): OutsiderEntity? {
        val id = rec.str("resource_id") ?: return null
        val first = rec.str("first_name").orEmpty()
        val middle = rec.str("middle_name").orEmpty()
        val last = rec.str("last_name").orEmpty()
        val full = listOf(first, middle, last).filter { it.isNotBlank() }.joinToString(" ")
        return OutsiderEntity(
            resourceId = id,
            fullName = full.ifBlank { "—" },
            firstName = first.ifBlank { null },
            middleName = middle.ifBlank { null },
            lastName = last.ifBlank { null },
            fatherName = rec.str("father_name"),
            dateOfBirth = rec.str("date_of_birth")?.let { ImportEngine.normalizeDate(it) },
            community = rec.str("community"),
            gender = rec.str("gender"),
            address1 = rec.str("address1"),
            address2 = rec.str("address2"),
            address3 = rec.str("address3"),
            address4 = rec.str("address4"),
            pinCode = rec.str("pin_code"),
            email = rec.str("personal_email_id"),
            mobile = rec.str("mobile_no"),
            aadhaar = rec.str("aadhaar_number"),
            pan = rec.str("pan_number"),
            education = rec.str("education_qualification"),
            approverPostId = rec.str("approver_post_id"),
            loginRequired = rec.str("login_required"),
            makerId = rec.str("maker_id"),
            remarks = rec.str("remarks"),
            officeId = rec.str("office_id"),
            officeName = rec.str("office_name"),
            subDivisionId = rec.str("sub_division_id"),
            divisionId = rec.str("division_id"),
            divisionName = rec.str("division_name"),
            bankAccNo = rec.str("bank_acc_no"),
            ifsc = rec.str("ifsc_code"),
            bankType = rec.str("bank_type"),
            resourceStatus = rec.str("resource_status"),
            payloadJson = payloadJson(rec)
        )
    }

    /**
     * Maps one HRMS arrangements row. Returns null for any row whose arrangement_type_desc is
     * not one of the three tracked categories, so the caller can simply drop nulls. The
     * substitute person is the outsource resource (resource_name) or, for GDS-emp cover, the
     * employee_name. Karur-only filtering is applied by the caller against the office master.
     */
    fun toArrangement(rec: Record): ArrangementEntity? {
        val type = rec.str("arrangement_type_desc")?.trim()
        val cat = when (type) {
            "GDS Post by Outsrc Resource" -> "GDS"
            "Non Sanc Post by Outsrc Resource" -> "NONSANC_OUT"
            "Non Sanc Post by GDS Emp" -> "NONSANC_GDS"
            else -> return null
        }
        val personName = rec.str("resource_name") ?: rec.str("employee_name")
        val personId = rec.str("resource_id")?.takeIf { it != "0" } ?: rec.str("employee_id")
        return ArrangementEntity(
            category = cat,
            arrangementType = type,
            post = rec.str("new_designation"),
            officeName = rec.str("new_office_name"),
            officeId = rec.str("new_office_id"),
            personId = personId,
            personName = personName,
            substituteId = personId,
            substituteName = personName,
            fromDate = rec.str("arrangement_from_date")?.let { ImportEngine.normalizeDate(it) },
            toDate = (rec.str("revised_arrangement_to_date") ?: rec.str("arrangement_to_date"))
                ?.let { ImportEngine.normalizeDate(it) },
            status = rec.str("approve_status")
        )
    }

    fun toRosterRow(rec: Record): MobileRosterEntity {
        fun get(vararg keys: String): String? = keys.firstNotNullOfOrNull { k ->
            rec.entries.firstOrNull { it.key.trim().equals(k, true) }?.value?.takeIf { it.isNotBlank() }
        }
        return MobileRosterEntity(
            serialNo = get("S.NO", "S.No", "Sl.No"),
            officeName = get("Name of the offices"),
            designation = get("Designation"),
            staffName = get("Name"),
            phone = get("Ph Number", "Ph Number ", "Phone"),
            altStaff = get("Alternative Staff")
        )
    }
}

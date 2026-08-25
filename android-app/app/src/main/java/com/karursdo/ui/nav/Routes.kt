package com.karursdo.ui.nav

import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object DirectoryRoute
@Serializable object MessageRoute
@Serializable data class OfficeDetailRoute(val officeId: String, val officeName: String)
@Serializable data class EmployeeDetailRoute(val type: String, val employeeId: String)
@Serializable data class OutsiderDetailRoute(val resourceId: String)
@Serializable object OfficeManagementRoute
@Serializable data class OfficeMasterDetailRoute(val officeId: String)
@Serializable object ArrangementsRoute
@Serializable object InspectionReportsRoute
@Serializable object ImportRoute
@Serializable object ProfileRoute
@Serializable object UserAdminRoute
@Serializable object EventsAdminRoute
@Serializable object MoRoute
@Serializable data class MoBeatListRoute(val beat: String)
@Serializable data class MoOfficeDetailRoute(val moOfficeId: Long)
@Serializable object CpvRoute
@Serializable data class CpvDetailRoute(val officeKey: String, val title: String)
@Serializable object BirthdaysRoute
@Serializable object RetirementsRoute

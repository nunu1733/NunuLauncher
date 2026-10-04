package app.lawnchair.organizer.planning

fun interface OrganizationPlanner {
    fun plan(input: OrganizationInput): PlanningResult
}

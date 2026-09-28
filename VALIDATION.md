# Validation record

## Passed locally

- Connected React frontend: production Vite build.
- Frontend: 13 Node tests (10 domain tests and 3 API transport/CSRF/error tests).
- Backend: 13 JUnit tests, with the Spring Boot application context, MockMvc, Spring Security and an H2 database in PostgreSQL mode. The schema migration applied successfully.
- Backend checks include real session-bound masked CSRF token round-trip, authentication/verified-email requirements, owner-ID rejection, optimistic locking, transactional validation failures, family visibility, invitation restrictions, timezone/month-end reminder scheduling and delivery deduplication.
- Legacy migration checks confirm that only PopCorn data enters an empty signed-in account, that the Google email remains unchanged, and that a second import is refused.
- Docker Compose file: validated with the installed `docker-compose config --quiet`.

## Execution limitations

The session's approval policy blocked downloading the remaining SMTP runtime artifacts and the Maven Surefire JUnit provider. The same backend test classes were compiled from source and executed through the locally cached JUnit Jupiter engine, using a temporary test classpath that omitted the missing SMTP providers. Reminder tests use a recording transport and make no external deliveries. Normal `./mvnw test` and Docker builds retain the full dependency list and are the intended repeatable commands once network access is available.

Not yet verified end-to-end: real Google OAuth login/callback, live PostgreSQL behavior, Docker image startup, and SMTP provider delivery into Mailpit. The local Docker/Colima engine was not running, and no Google OAuth client credentials were supplied. These are explicit local setup requirements, not mocked production login capabilities. No development authentication bypass is included.

## Backend test results

```text
SUCCESSFUL rejectsEditingAnotherUser()
SUCCESSFUL rejectsUnverifiedGoogleEmail()
SUCCESSFUL supportsThreeMembersAndRevokesVisibilityOnLeave()
SUCCESSFUL requiresAuthenticationAndCsrf()
SUCCESSFUL reminderStopsWhenTallied()
SUCCESSFUL createsSeparateGoogleUsersWithoutDemoRecords()
SUCCESSFUL rejectsInvalidMoneyAndDatesWithoutPartialSave()
SUCCESSFUL reminderClampsLeapMonthAndDeduplicates()
SUCCESSFUL browserCsrfTokenWorksWithSessionCookie()
SUCCESSFUL savesOwnRecordsAndRejectsStaleRevision()
SUCCESSFUL invitationChecksEmailExpiryAndSingleUse()
SUCCESSFUL contextLoads()
TESTS=12 FAILURES=0
```

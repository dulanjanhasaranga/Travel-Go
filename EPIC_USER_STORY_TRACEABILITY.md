# TravelGO: business document → website traceability

Reviewed: 10 September 2026.

Update, 12 September 2026: this table preserves the original audit findings. Subsequent account, session, review and UI changes are documented in WEBSITE_UX_UPGRADE_REVIEW.md; email, inquiry, catalogue and dashboard changes are documented in IMPLEMENTATION_PROGRESS.md. Read those implementation reports alongside this baseline before treating an individual gap as current. The complete document-derived backlog is still not fully implemented.

**Conclusion: all four epics are represented, but the website does not fully satisfy every documented activity or customer requirement. EP02's core catalogue and EP04's core simulated financial sequence are the strongest. EP01 has access-control and account-workflow gaps; EP03 has substantial communication and post-booking gaps.**

## Source and interpretation

Source: [TravelGo_IE3121_Business_Architecture_Assessment (2).docx](<C:/Users/User/Downloads/ISE/ISE/TravelGo_IE3121_Business_Architecture_Assessment (2).docx>). I re-extracted its text and inspected its embedded stakeholder chart, four epic diagrams and requirement-gathering email image. The email contains requirements not present in the paragraph extraction.

The report is principally an **As-Is business assessment**, not an approved To-Be specification or numbered user-story backlog. The IDs below are reviewer-created stories derived from its scope, numbered activities, decisions and handovers; they are not original document IDs. “Missing” means no implemented workflow was found, not that every manual As-Is action must automatically be built. Management approval and other scope decisions need agreement in a To-Be specification.

Status meanings: **Supported** = connected implementation for the stated narrow story, with no identified gap in that story during this review; **Partial** = some pieces exist but the outcome or controls are incomplete; **Missing** = no connected implementation found; **Deferred** = absent but explicitly postponed in the earlier agreed scope. None is a blanket production-readiness claim.

## EP01 — User, Staff & System Administration

Document reference: §5.1, scope; activities 01–15; decision/handovers; Figure 2.

| Derived story | Website and persistence evidence | Status and clarification / acceptance requirement |
|---|---|---|
| EP01-S01 — As a customer, register with valid, unique account details | `/auth/register`; `AuthController`, `UserService`, `users` | **Supported** for direct self-registration, validation and duplicate-email handling. It does not create an admin-reviewed account request as in the manual process. Record direct registration as a deliberate To-Be change. |
| EP01-S02 — As a user, sign in and out and reach my role's workspace | `/auth/login`; `SecurityConfig`, `CustomUserDetailsService`, `users`, `roles` | **Supported** for session login, logout and fixed roles. Simulated social login and unsupported remember-me were removed. |
| EP01-S03 — As a customer/staff member, view and update my own profile | Admin can edit records at `/admin/users/{id}` and `/admin/staff/{id}/edit` | **Partial**: administrative editing exists; no self-service profile/change-request page or controller was found. Add owner-checked profile editing or explicitly limit this story to admin processing. |
| EP01-S04 — As an administrator, create and maintain staff/customer records | `/admin/staff`, `/admin/users`; `StaffManagementController`, `UserManagementController`; `users` | **Supported** for staff creation, account lists, details and administrative updates. Customer creation is through registration. |
| EP01-S05 — As an administrator, activate/deactivate an account and stop its access | Account toggle routes; `UserService`; `users.active` | **Partial**: fresh login is blocked after deactivation, but an existing disabled consultant session can still perform catalogue writes. Revalidate or revoke sessions consistently. |
| EP01-S06 — As an administrator, assign a role and enforce its access | `/admin/users/{id}/assign-role`; `roles`, user-role relationship; `SecurityConfig` | **Partial**: role assignment and fixed-role route rules exist; role changes need consistent handling for already authenticated sessions. Document consultant = catalogue/booking/inquiry and officer = visa/payment. |
| EP01-S07 — As an administrator, configure effective permissions | `/admin/roles/{id}`, `/admin/permissions`; `RoleController`, `RoleService`; `permissions`, `role_permissions` | **Partial**: checkboxes persist, and the page now renders, but authorization uses `ROLE_*`, not these permission assignments. A changed checkbox must actually alter access, or the editing UI must be removed/relabeled. |
| EP01-S08 — As management, approve/reject sensitive access and setting changes | Figure 2 management lane, §5.1 activities 09–10 | **Missing**: no approval-request record, management queue, threshold or approval audit. Decide whether management approval remains manual or becomes an application story. |
| EP01-S09 — As an account requester, receive missing-info/status messages | Registration validation and immediate flash messages | **Partial**: no account-request lifecycle, correction conversation or durable account-status notification workflow. Field errors are not a substitute for the documented staff/customer handover. |
| EP01-S10 — As admin, manage company/contact/hours and system availability | `/admin/settings`; `SystemSettingsController/Service`; `system_settings` | **Supported** for company/contact/hours and maintenance state. The official logo is deliberately fixed to the user-supplied asset; payment/notification configuration mentioned in Figure 2 is not a full configuration subsystem. |
| EP01-S11 — As admin/management, review system activity and operational reports | Admin dashboard aggregates; visa history and payment records | **Partial**: no general access-change/system audit UI or complete reporting module. New reports were **deferred**. Do not call payment history a complete system audit trail. |

## EP02 — Package, Destination & Hotel Management

Document reference: §5.2 scope, catalogue activities and Figure 3; embedded requirement email.

| Derived story | Website and persistence evidence | Status and clarification / acceptance requirement |
|---|---|---|
| EP02-S01 — As catalogue staff, create/update destinations with country, city, description and images | `/staff/destinations`; `AdminDestinationController`, `DestinationService`; `destinations` | **Supported** for consultant-managed records and public display. Controller name says “Admin”, but actual permitted role is consultant. |
| EP02-S02 — As catalogue staff, maintain package description, duration, price and services | `/staff/packages`; `AdminTourPackageController`, `TourPackageService`; `tour_packages`, `package_categories` | **Supported** for core package CRUD, category and destination associations. |
| EP02-S03 — As catalogue staff, maintain flight information included in packages | Package forms and public package cards; `TourPackage` flight information | **Supported** as information. Live airline booking is explicitly outside document scope. |
| EP02-S04 — As catalogue staff, maintain hotel name, location, description, pictures and prices | `/staff/hotels`; `AdminHotelController`, `HotelService`; `hotels` → `destinations` | **Supported** for catalogue records. This is not a live partner reservation service, which is outside EP02 scope. |
| EP02-S05 — As staff, publish/unpublish available catalogue items | Active flags and capacity fields; public `findAll`/filter queries | **Partial**: active flags exist and booking validation rejects inactive selections, but public listing does not consistently filter inactive records and no complete publish/unpublish control was found. Unavailable items should be hidden or clearly unavailable before submission. |
| EP02-S06 — As a visitor, browse/search destinations and view relevant packages | `/destinations`, `/packages`; `PublicController`; destination/category/query filters | **Supported** for current text, destination and category filters. Public browsing does not require login; amend Figure 3's mandatory-login path for the To-Be process. |
| EP02-S07 — As a visitor, search packages by destination, date or price | Embedded email; `/packages` currently accepts `search`, `destinationId`, `categoryId` | **Partial**: destination exists; travel-date and price-range filters are missing. Add both frontend fields and matching backend queries. |
| EP02-S08 — As a customer, inspect hotel information before choosing | Booking form dropdown shows hotel name, stars and nightly price; booking detail shows selected hotel | **Partial**: destination-linked selection exists, but no rich pre-selection hotel description/photo browsing as depicted in Figure 3. Add a detail card/modal before choosing. |
| EP02-S09 — As catalogue staff, manage reusable transport options | Embedded email; package flight/included-services text | **Partial**: transport can be described as text, but there is no reusable transport catalogue/entity/selection workflow. Clarify whether descriptive text satisfies the requirement. |

## EP03 — Booking & Customer Journey

Document reference: §5.3 activities 1–10, decisions/handovers and Figure 4.

| Derived story | Website and persistence evidence | Status and clarification / acceptance requirement |
|---|---|---|
| EP03-S01 — As a customer, select a package, travel date and complete traveller details | `/packages/{id}/book` → `/customer/bookings/create`; `BookingRequest`, `BookingService`; `bookings`, `travelers` | **Supported**: validated lists, positive count/capacity, active package/destination and a future payment deadline; booking/travellers/hotel save atomically. |
| EP03-S02 — As a customer, add an optional destination-matched hotel separately from the base price | Same form/service; `booking_hotels` → booking/hotel | **Supported** for optional selection, room/night calculation, checkout and separate cost. Hotel choice itself is not currently replaceable through booking modification. |
| EP03-S03 — As a customer, receive a pending booking record and see my bookings | `/customer/bookings`, `/customer/bookings/{id}`, dashboard | **Supported** for owned records, current status and immediate submission acknowledgement. “Booking received” must not be described as final paid confirmation. |
| EP03-S04 — As a consultant, review and confirm an eligible booking | `/staff/bookings`, `/{id}/confirm`; `BookingService.confirmBooking` | **Supported under the agreed newer rule**: approved visa and both successful payments are required; final payment also confirms automatically. Figure 4's standalone consultant confirmation needs an EP04 dependency. |
| EP03-S05 — As a consultant, request more booking information and re-review the reply | Figure 4's missing-information loop | **Missing**: no request-info endpoint/state/conversation. Complete form validation prevents some bad submissions but does not provide this workflow. |
| EP03-S06 — As a consultant, decline/cancel a booking with a recorded reason | §5.3 activity 6 and Figure 4 | **Missing** as a consultant operation: staff booking controller exposes confirmation only. Customer eligible cancellation and visa-rejection cancellation are different workflows. |
| EP03-S07 — As a customer, modify/cancel an eligible booking | `/customer/bookings/{id}/modify`, `/{id}/cancel`; `BookingService`, `WorkflowRules` | **Supported within current policy**: pending only, no payment record, no review started; stale updates rejected, replacement travellers and hotel dates/prices updated together. Make these conditions explicit in the To-Be story. |
| EP03-S08 — As a customer, see status history, not only the latest status | Booking lists/details; `visa_status_history` is recorded separately | **Partial**: current status and payment history exist; no complete customer booking event timeline was found. Timelines were **deferred**. |
| EP03-S09 — As a customer, receive booking received/change/cancellation/confirmation notifications | Flash messages; `/customer/notifications`; visa transition notifications | **Partial**: notifications exist for visa transitions, but booking creation/edit/cancel and final payment do not consistently generate durable booking notifications. Verify each named event, not merely that a notifications page exists. |
| EP03-S10 — As a customer, submit an inquiry and receive a staff response | `/contact` → `/staff/inquiries/{id}/reply`; `ContactMessageService`; `contact_messages` | **Partial**: submission and reply persist; reply is marked resolved but is not delivered to a customer inbox/email/SMS. “Reply sent successfully” overstates actual behaviour. |
| EP03-S11 — As staff, complete a trip; as its customer, leave and view one valid post-trip rating | `/customer/reviews`; `CustomerReviewController`, `ReviewService`; `reviews` | **Partial**: review POST exists, but a pending booking and rating 99 are accepted by the backend. No connected completed-trip transition or saved-review display was found. Require trip eligibility and rating 1–5 server-side; display the result. |

## EP04 — Visa & Payment Management

Document reference: §5.4 activities 1–10, decisions/handovers and Figure 5.

| Derived story | Website and persistence evidence | Status and clarification / acceptance requirement |
|---|---|---|
| EP04-S01 — As staff, add/update visa types and requirements | §5.4 activity 1; `VisaType.java`; `/visa-info` | **Missing** for staff management: types are the compiled enum `VISIT`, `WORK`; guidance is static HTML. Add persisted types and officer management, or revise the story to a fixed list. |
| EP04-S02 — As a customer, choose a visa type and apply for my booking | `/customer/visas`, `/apply`; `CustomerVisaController`, `VisaApplicationService`; `visa_applications` → booking | **Supported** for Visit/Work and owned eligible bookings. Public country-specific visa cards should not imply additional configured application types. |
| EP04-S03 — As a customer, upload valid documents and access them privately | `/customer/visas/{id}/documents`, `/visa-documents/{id}/download`; `VisaDocumentService`; `visa_documents` plus upload directory | **Supported** for PDF/JPEG/PNG validation, size limits, document-submission stage and owner/officer download. Not a proof of legal authenticity. |
| EP04-S04 — As the system/officer, identify missing documents, notify the applicant and accept correction | Figure 5 checklist/re-upload loop; `markDocumentsVerified` | **Partial**: verification requires at least one file and officer assessment, not a complete configurable checklist. No structured missing-item request/response loop. Required-document configuration was **deferred**. |
| EP04-S05 — As an officer, verify documents and issue nonnegative visa/documentation charges | `/staff/visas` verification/charges routes; `VisaApplicationService` | **Supported**: correct stage, positive total and charge lock; 24-hour quote and explicit unchanged reissue after expiry. |
| EP04-S06 — As a customer, pay the correct upfront amount and begin processing | `/customer/payments/checkout/{bookingId}` → `/process`; `PaymentService`; `payments` | **Supported for simulation**: stored visa + documentation charges, quote check, deadline and row lock; success starts processing; duplicate success is idempotent. |
| EP04-S07 — As finance staff/customer, handle a failed payment and retry with clear alerts | Customer checkout error flash; officer pending/failed status operations and new attempts | **Partial**: validation errors and manual failed records can be retried, but customer simulated checkout has no substantive card-decline/timeout simulation, durable failure notification or reminder workflow. No live gateway is required. |
| EP04-S08 — As officer, record an embassy decision and let the customer track it | `/staff/visas/{id}/approve` or `/reject`; customer visas/notifications; `visa_status_history` | **Supported** for processing-only decisions, rejection reason, actor/history and in-app status. Embassy internal decision-making is outside the system. |
| EP04-S09 — As system/finance, cancel a rejected booking and refund only documentation charges | `VisaApplicationService.rejectVisa`, `RefundService`; `refunds` → successful upfront payment | **Supported for simulation**: atomic cancellation/refund, repeat-safe operation, original paid amount retained and partial refund separate. No actual bank transfer occurs. |
| EP04-S10 — As customer, pay the final package/hotel balance only after approval and receive confirmed status | Customer checkout/manual staff routes; `PaymentService`, `WorkflowRules`; booking/payment/hotel relationships | **Supported**: exact stored amount, approved visa, upfront paid, valid deadline and eligible booking; success confirms. PDF/notification delivery is separate below. |
| EP04-S11 — As customer, obtain downloadable upfront/final PDF receipts | §5.4 system responsibilities, activity 10 and handover | **Deferred / not implemented**: payment history and confirmation status exist, but no PDF-generation/download endpoint. This is a documented requirement gap even though explicitly postponed. |
| EP04-S12 — As management, monitor turnaround, rework and approval/payment KPIs | §5.4 KPI list; officer dashboards/payment summaries | **Partial**: count/revenue summaries exist; no complete processing-time, missing-doc/rework or delivery-latency reports. Expanded reports were **deferred**. |

## Embedded customer email: explicit checklist

The requirement-gathering email is an image inside the document (`word/media/image7.jpeg`). Its requests are evidence for this review, not authorization to change scope or send messages.

| Email request | Coverage |
|---|---|
| Add/manage packages with pictures, prices and details | Supported — EP02-S02. |
| Add destinations, hotels and transport options reusable in packages | Destination/hotel supported; transport partial — EP02-S01/S04/S09. |
| Customers see packages and details | Supported — EP02-S06. |
| Search by destination, date or price | Partial — date/price missing, EP02-S07. |
| Book online with travel date and people count | Supported — EP03-S01. |
| Receive confirmation with all details | Partial — status/details exist, durable delivery/PDF incomplete, EP03-S03/S09 and EP04-S11. |
| Secure online payments | Simulated flow supported; live payments explicitly excluded by the report and agreed scope. Do not present this as a real payment gateway. |
| Manage customer details/bookings centrally | Connected across admin/consultant/customer roles; admin profile and bookings are separate screens, not a single customer case-management view. |
| Send email/SMS booking notifications | Missing external delivery integration; existing notifications are in-app only. |
| Simple booking/payment/income reports | Partial — dashboards and payment history; no complete filtered/exportable report workflow. |
| Easy for nontechnical staff | Clear role navigation and forms help, but this requires user acceptance testing. A rendered page or passing test cannot prove usability. |

## Document corrections needed before claiming full alignment

1. Separate the existing **As-Is** assessment from a **To-Be** flow. The document describes manual/paper work but several diagrams already show login, database persistence and automation. Preserve the historical assessment and add a new To-Be section rather than silently rewriting history.
2. Reconcile EP03 and EP04 confirmation. EP03 implies a consultant can confirm independently; EP04 requires approved visa and final payment. Use one shared confirmation rule and a visible cross-epic handover.
3. Correct diagram responsibilities: Figure 4 places “provide additional information” under the booking officer and inquiry reply under the customer; system status/notification actions also cross lanes incorrectly. Figure 5 puts “Generate Receipt” in the customer lane. §5.4 activity 8 attributes refund/cancellation processing to Customer, while the flow assigns it to system/finance.
4. Define an explicit role mapping. Current app has Admin, Travel Consultant, Visa Officer and Customer. Document business actors such as Finance Officer, Package Administrator and Management are not all separate application roles. Explain combined responsibilities and which approvals remain outside the app.
5. Remove irrelevant template validation items such as stock availability and the USD 5,000 threshold unless they are actual requirements. The payment-retry validation comment currently describes missing documents instead of retries.
6. State the deadline rules precisely in one place: quote issue +24 hours; final payment before travel-date midnight minus three days, with an explicit timezone. Define editing eligibility and the documentation-only rejection refund rule alongside those deadlines.
7. Clarify catalogue availability as an active/capacity check versus real supplier inventory. Live flight booking and hotel reservation are outside the written EP02 scope; their absence must not be counted as a mandatory academic implementation defect.
8. Treat KPIs as proposed measurements, not achieved improvements. Missing timestamps/events must be captured before measuring rework or delivery latency reliably.

## Website clarity gaps

- `/visa-info` shows country-specific marketing cards and promises such as fast-track submission/grant delivery, while applications only select Visit/Work and staff manually records a decision. Explain these distinctions and remove unsupported operational promises.
- FAQ base-package language implies universal inclusions and visa assistance; present the package's actual inclusions and make separately quoted visa/documentation charges explicit everywhere, as on the refreshed homepage.
- Customer dashboard mentions payment receipts; until PDFs exist, call this payment history rather than suggesting a downloadable receipt.
- Inquiry staff success text should say “reply saved” until a customer can actually receive it.
- Permission checkboxes imply effective access control despite only persisting configuration. This is a functional and communication gap.
- Show a next action for each booking/visa state: upload documents, wait for review, pay before deadline, wait for decision, pay final balance, or contact staff after expiry/rejection.

## Evidence and verification boundaries

- Reviewed source controllers, services, entities, repositories and Thymeleaf templates; source paths below provide the implementation trail.
- Fresh command: `mvn -B -l target/epic-story-audit.log -Dtest=FeatureConnectivityAuditIT test` — **26 tests passed, no failures/errors/skips**, using isolated H2 `travelgo-test` with initializer disabled. The application database and seed accounts were not used for test mutations.
- These include page rendering/form-route checks, registration/login/logout, catalogue/admin persistence, booking → visa → payment → confirmation, document ownership, workflow/financial constraints and concurrency scenarios.
- **Four passing characterization tests reproduce defects**: ineffective permission edits, inquiry reply without delivery, invalid/premature reviews, and disabled-staff-session catalogue writes. Passing tests confirm those gaps remain; they do not certify full story completion.
- Earlier disposable MySQL and browser checks are documented in `CONNECTIVITY_AUDIT.md` and `WORKFLOW_AND_DESIGN_REVIEW.md`; this turn did not repeat MySQL locking verification or manually exercise every screen in a browser.
- No business implementation was changed for this audit. This report is a traceability assessment, not an exhaustive security/performance/accessibility certification.

Implementation references:

- [SecurityConfig](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/config/SecurityConfig.java>), [CustomUserDetailsService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/CustomUserDetailsService.java>), [RoleService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/RoleService.java>).
- [PublicController](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/controller/PublicController.java>), [TourPackageRepository](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/repository/TourPackageRepository.java>), [booking form](<C:/Users/User/Downloads/ISE/ISE/src/main/resources/templates/customer/booking-form.html>).
- [BookingService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/BookingService.java>), [StaffBookingController](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/controller/StaffBookingController.java>), [ContactMessageService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/ContactMessageService.java>), [CustomerReviewController](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/controller/CustomerReviewController.java>), [NotificationService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/NotificationService.java>).
- [VisaApplicationService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/VisaApplicationService.java>), [VisaDocumentService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/VisaDocumentService.java>), [PaymentService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/PaymentService.java>), [RefundService](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/RefundService.java>), [WorkflowRules](<C:/Users/User/Downloads/ISE/ISE/src/main/java/com/travelgo/service/WorkflowRules.java>).
- [FeatureConnectivityAuditIT](<C:/Users/User/Downloads/ISE/ISE/src/test/java/com/travelgo/FeatureConnectivityAuditIT.java>), [WorkflowIntegrationTests](<C:/Users/User/Downloads/ISE/ISE/src/test/java/com/travelgo/WorkflowIntegrationTests.java>), [fresh audit log](<C:/Users/User/Downloads/ISE/ISE/target/epic-story-audit.log>).

## Suggested completion order

1. Fix effective permissions/disabled sessions and post-trip review validation. These are implementation defects, not optional visual enhancements.
2. Finish EP03 consultant request-info/decline, durable booking notifications and customer inquiry reply delivery.
3. Add date/price filters and consistent active catalogue visibility; improve hotel preview details.
4. Agree fixed versus managed visa types, then add missing-document requests/checklists if included in the next phase.
5. Explicitly schedule the deferred receipt/history/report work and email/SMS delivery. Approve a To-Be story list with acceptance criteria before calling all four epics complete.

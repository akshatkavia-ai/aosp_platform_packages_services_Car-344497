# Playwright Locator Mapping

This repository does not currently contain a Playwright project, Playwright config, or Playwright spec files.  
As a result, this locator map is derived from Android UI layout XML resources and the activity/fragment code that binds those layouts.

## Test-run context

- Playwright command attempted: `CI=true npx playwright test --reporter=list`
- Result: `Error: No tests found`
- Interpretation: there is no Playwright suite in this repository yet

## Recommended locator strategy

For this codebase, prefer stable Android selectors in this order:

1. **Resource ID**
   - Espresso: `withId(R.id.element_id)`
   - UIAutomator/Appium: `By.res("<appPackage>:id/element_id")`
2. **Content description** if present
3. **View hierarchy position** only as a last resort
4. **Visible text** only when the text is fixed and intentionally stable

If these screens are later automated through a Playwright-adjacent mobile harness, map to the same Android `resource-id` values first.

## Locator mapping

| Page / Screen | Source layout | Element purpose | Selector present in UI | Recommended stable locator |
|---|---|---|---|---|
| Car Diagnostic Verifier / MainActivity | `tests/CarDiagnosticVerifier/res/layout/verifier_activity.xml` | Top status text | `@id/status_bar` | `resource-id=status_bar` |
| Car Diagnostic Verifier / MainActivity | `tests/CarDiagnosticVerifier/res/layout/verifier_activity.xml` | Verification results list | `@id/verification_results` | `resource-id=verification_results` |
| Default Storage Monitoring Companion / MainActivity | `tests/DefaultStorageMonitoringCompanionApp/res/layout/activity_main.xml` | Main notification/status text | `@id/notification` | `resource-id=notification` |
| UX Restrictions Sample / MainActivity | `tests/UxRestrictionsSample/res/layout/main_activity.xml` | Driving state label | `@id/driving_state` | `resource-id=driving_state` |
| UX Restrictions Sample / MainActivity | `tests/UxRestrictionsSample/res/layout/main_activity.xml` | Distraction optimization status | `@id/do_status` | `resource-id=do_status` |
| UX Restrictions Sample / MainActivity | `tests/UxRestrictionsSample/res/layout/main_activity.xml` | UX restriction status | `@id/uxr_status` | `resource-id=uxr_status` |
| UX Restrictions Sample / MainActivity | `tests/UxRestrictionsSample/res/layout/main_activity.xml` | Toggle restrictions button | `@id/toggle_status` | `resource-id=toggle_status` |
| UX Restrictions Sample / MainActivity | `tests/UxRestrictionsSample/res/layout/main_activity.xml` | Main paged list content | `@id/paged_list_view` | `resource-id=paged_list_view` |
| USB AOAP Host / UsbAoapHostActivity | `tests/usb/AoapHostApp/res/layout/host.xml` | USB log output area | `@id/usb_log` | `resource-id=usb_log` |
| Trust Agent / CarEnrolmentActivity | `TrustAgent/res/layout/car_enrolment_activity.xml` | Scroll container for enrollment content | `@id/scroll` | `resource-id=scroll` |
| Trust Agent / CarEnrolmentActivity | `TrustAgent/res/layout/car_enrolment_activity.xml` | Enrollment status/details text | `@id/textfield` | `resource-id=textfield` |
| Trust Agent / CarEnrolmentActivity | `TrustAgent/res/layout/car_enrolment_activity.xml` | Start advertising button | `@id/start_button` | `resource-id=start_button` |
| Trust Agent / CarEnrolmentActivity | `TrustAgent/res/layout/car_enrolment_activity.xml` | Revoke trust button | `@id/revoke_trust_button` | `resource-id=revoke_trust_button` |
| Embedded Kitchen Sink / UsbHostManagementActivity | `tests/EmbeddedKitchenSinkApp/res/layout/usb_host.xml` | Progress container while resolving handlers | `@id/usb_handlers_progress` | `resource-id=usb_handlers_progress` |
| Embedded Kitchen Sink / UsbHostManagementActivity | `tests/EmbeddedKitchenSinkApp/res/layout/usb_host.xml` | USB handler list | `@id/usb_handlers_list` | `resource-id=usb_handlers_list` |
| Embedded Kitchen Sink / DiagnosticTestFragment | `tests/EmbeddedKitchenSinkApp/res/layout/diagnostic.xml` | Live diagnostic info text | `@id/live_diagnostic_info` | `resource-id=live_diagnostic_info` |
| Embedded Kitchen Sink / DiagnosticTestFragment | `tests/EmbeddedKitchenSinkApp/res/layout/diagnostic.xml` | Freeze-frame diagnostic info text | `@id/freeze_diagnostic_info` | `resource-id=freeze_diagnostic_info` |
| OBD2 App / MainActivity | `tests/obd2_app/res/layout/activity_main.xml` | Root activity container | `@id/activity_main` | `resource-id=activity_main` |
| OBD2 App / MainActivity | `tests/obd2_app/res/layout/activity_main.xml` | Status output area | `@id/statusBar` | `resource-id=statusBar` |
| OBD2 App / MainActivity | `tests/obd2_app/res/layout/activity_main.xml` | Connect button | `@id/connection` | `resource-id=connection` |
| OBD2 App / MainActivity | `tests/obd2_app/res/layout/activity_main.xml` | Settings button | `@id/settings` | `resource-id=settings` |

## Screen-to-layout bindings used

The following code references were used to ground screen names:

- `CarDiagnosticVerifier MainActivity -> R.layout.verifier_activity`
- `DefaultStorageMonitoringCompanionApp MainActivity -> R.layout.activity_main`
- `UxRestrictionsSample MainActivity -> R.layout.main_activity`
- `UsbAoapHostActivity -> R.layout.host`
- `CarEnrolmentActivity -> R.layout.car_enrolment_activity`
- `EmbeddedKitchenSink UsbHostManagementActivity -> R.layout.usb_host`
- `EmbeddedKitchenSink DiagnosticTestFragment -> R.layout.diagnostic`
- `OBD2 App MainActivity -> R.layout.activity_main`

## Notes for future automation

- Confirm each module’s runtime package name from its manifest before writing automation code that uses fully qualified Android resource locators.
- Prefer assertions against IDs and state-bearing views instead of raw text where possible.
- If a Playwright suite is later introduced for a browser-based surface, keep this document as the canonical source for stable element intent and convert entries to framework-specific locators as needed.

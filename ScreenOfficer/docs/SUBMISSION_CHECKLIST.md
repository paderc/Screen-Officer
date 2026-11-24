# ScreenOfficer M2 submission checklist

Checked items were verified on the Android emulator or by the build checks. Unchecked items still need evidence or packaging for submission.

## Verified on the current build

- [x] Build the debug APK and pass lint, 23 unit tests, and 8 Android tests.
- [x] Give Clock and Files one shared limit. Reach it, verify the blocking window over both apps, and use the soft block’s five-minute extension.
- [x] View usage by app in History, move between days, and verify individual clearing for the Games limit and the shared limit.
- [x] Reload saved rules from local storage in Android tests and verify that clearing one rule preserves the other rule and its extension.

## Capture the remaining demo evidence

- [ ] Save a limit, force-stop ScreenOfficer, and reopen it without clearing storage. Verify the saved limit is still present. Rotate during app selection and check that the selection survives.
- [ ] Confirm that Android Usage Access and the Accessibility Service count toward the mobile capability criterion. With Usage Access on, disable the service and check that History and saved limits are available while blocking pauses. Re-enable the service and trigger the block again.
- [ ] Revoke Usage Access. Show the unavailable state in History, follow the Settings prompt, and confirm usage returns after granting access.
- [ ] Enable airplane mode with Wi-Fi off and demonstrate ScreenOfficer’s on-device core flow using real Android usage measurements for Clock and Files.
- [ ] Confirm lecturer approval for the separate Week 7 networking exercise. Include its source and show loading, a successful response, an empty response, a failed request, an unexpected response, loss of connectivity, and recovery with retry. Label any mock service, fixture data, or simulated response.

## Package and submit

- [ ] Commit the version being demonstrated. Record the repository link and exact commit ID, and provide the source tag or snapshot if used.
- [ ] Attach the matching APK from `app/build/outputs/apk/debug/app-debug.apk` or provide the agreed demonstration access.
- [ ] Include the [architecture brief](ARCHITECTURE_BRIEF.md), this checklist, and `AI_USE.md` with one reviewed change, the team’s decision, and the test result.
- [ ] In WIKAMP, include the repository link, exact commit ID, and a short progress note with the core flow, verified evidence, and known limitations.

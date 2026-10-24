# Release shrinking rules. Compose, Hilt, Navigation and kotlinx.serialization ship
# their own consumer rules; add app-specific keeps here only with a comment saying why.

# A crash report carries class names only, never a message (the owner's rule, 2026-10-23; CrashScrubber,
# RelaySafetyNet.Scrubbed): the scrubbed copy's message IS the original's class name, so our exception classes keep
# their names, or a release or staging report would read "j05" for RelayException. Names only: still shrunk if unused.
-keepnames class com.vettid.** extends java.lang.Throwable
# The scrubbed copies themselves: the first word of every scrubbed report.
-keepnames class com.vettid.core.data.vault.RelaySafetyNet$Scrubbed
-keepnames class com.vettid.core.data.vault.CrashScrubber$ScrubFailed

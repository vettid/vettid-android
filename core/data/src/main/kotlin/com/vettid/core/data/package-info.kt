/**
 * Repositories and local storage (ANDROID-PLAN §5). A2: the device's encrypted
 * storage ([KeystoreFileStore]) and [VaultSession]. A3: the repositories the
 * features use (`vault.AccountRepository`, `VaultRepository`,
 * `CredentialRepository`), implemented by `vault.VaultManager`; the app
 * environment (`env.AppEnvironment`) and the member session (`account`);
 * the PIN and password policies (`policy`); DataStore preferences (`prefs`);
 * and the biometric app lock (`lock.AppLock`, D6). Room caches per feature
 * follow in A4 and A5.
 */
package com.vettid.core.data

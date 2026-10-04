# R8 rules shipped with :core:crypto (a plain jar: R8 reads META-INF/proguard/).
#
# None are needed. The module calls BouncyCastle's low-level classes directly
# (HPKE, X-Wing, ML-KEM, X25519, Ed25519, XChaCha20-Poly1305, Argon2id, SHA-3)
# and never registers or looks up the BC JCA provider, so nothing is reached
# by reflection or by name. Verified in phase A1: a minified build ran a full
# HPKE MLKEM768X25519 seal/open, Argon2id, XChaCha20-Poly1305 and Ed25519 on
# the test phone, and R8 reported no missing classes. Add a keep here only
# with a comment saying which BC class is loaded reflectively and why.

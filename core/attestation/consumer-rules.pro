# R8 rules for :core:attestation. None are needed: the module uses BouncyCastle's
# ASN.1 classes directly and the platform's JCA (X.509, PKIX, ECDSA), with no
# reflection (verified with a minified build on the test phone in phase A1).

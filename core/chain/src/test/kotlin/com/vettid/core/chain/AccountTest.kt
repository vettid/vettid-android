package com.vettid.core.chain

import fr.acinq.bitcoin.Block
import fr.acinq.bitcoin.Descriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The watch-only account: BIP84 and BIP86 published vectors, the vault's descriptors, and what the parser refuses. Public data only. */
class AccountTest {
    // BIP84 spec, account 0 of the test phrase, xpub form of the published zpub. BIP86 spec, account 0.
    private val bip84Xpub =
        "xpub6CatWdiZiodmUeTDp8LT5or8nmbKNcuyvz7WyksVFkKB4RHwCD3XyuvPEbvqAQY3rAPshWcMLoP2fMFMKHPJ4ZeZXYVUhLv1VMrjPC7PW6V"
    private val bip86Xpub =
        "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ"
    private val fp = "73c5da0a"

    private fun main84() = Account.fromDescriptors("wpkh([$fp/84'/0'/0']$bip84Xpub/0/*)", "wpkh([$fp/84'/0'/0']$bip84Xpub/1/*)",
        Network.MAINNET)
    private fun main86() = Account.fromDescriptors("tr([$fp/86'/0'/0']$bip86Xpub/0/*)", "tr([$fp/86'/0'/0']$bip86Xpub/1/*)",
        Network.MAINNET)

    @Test
    fun bip84_vector_mainnet() {
        val a = main84()
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", a.address(false, 0))
        assertEquals("bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g", a.address(false, 1))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", a.address(true, 0))
        assertEquals("m/84'/0'/0'/1/0", a.path(true, 0))
    }

    @Test
    fun bip86_vector_mainnet() {
        val a = main86()
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", a.address(false, 0))
        assertEquals("bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh", a.address(false, 1))
        assertEquals("bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7", a.address(true, 0))
        assertEquals(32, a.derivationKey(false, 0).size)
    }

    @Test
    fun the_vaults_descriptors_for_every_network_give_the_vaults_addresses() {
        // The plan's check against `wallet.address.list`: here against the vault's own btc.Account (vector wallets).
        val expect = mapOf(
            "mainnet" to ("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu" to "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr"),
            "testnet4" to ("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl" to null),
            "signet" to ("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl" to null),
        )
        for ((name, w) in Vectors.wallets) {
            assertEquals("73c5da0a", w.fingerprintHex)
            assertEquals(2, w.accounts.size)
            expect[name]?.let { (p2wpkh, p2tr) ->
                assertEquals(p2wpkh, w.account(AccountType.P2WPKH).address(false, 0))
                if (p2tr != null) assertEquals(p2tr, w.account(AccountType.P2TR).address(false, 0))
            }
        }
        assertTrue(Vectors.wallets.getValue("regtest").account(AccountType.P2WPKH).address(false, 0).startsWith("bcrt1q"))
        assertTrue(Vectors.wallets.getValue("regtest").account(AccountType.P2TR).address(true, 3).startsWith("bcrt1p"))
    }

    @Test
    fun genesis_hashes_of_the_networks() {
        // The library prints a hash in byte order; Esplora and block explorers show it reversed (the form compared here).
        fun shown(n: Network) = n.chainHash.value.toByteArray().toDisplayHex()
        assertEquals("00000000da84f2bafbbc53dee25a72ae507ff4914b867c565be350b0da8bf043", shown(Network.TESTNET4))
        assertEquals("000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", shown(Network.TESTNET))
        assertEquals("00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6", shown(Network.SIGNET))
        assertEquals("000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", shown(Network.MAINNET))
        assertEquals("0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", shown(Network.REGTEST))
        assertEquals(Block.Testnet4GenesisBlock.hash, Network.TESTNET4.chainHash)
        assertEquals(Network.TESTNET4, Network.fromVaultName("testnet4"))
        assertEquals(null, Network.fromVaultName("testnet3"))
    }

    @Test
    fun descriptor_parser_accepts_the_vaults_shapes_and_refuses_the_rest() {
        val w = Vectors.wallets.getValue("testnet4")
        fun descriptors(type: String) = Vectors.descriptorsOf("testnet4", type)
        val (r, c) = descriptors("p2wpkh")
        // With a valid checksum (bitcoin-kmp computes it) and with `h` for the hardened marker.
        Account.fromDescriptors("$r#${Descriptor.checksum(r)}", "$c#${Descriptor.checksum(c)}", Network.TESTNET4)
        Account.fromDescriptors(r.replace("'", "h"), c.replace("'", "h"), Network.TESTNET4)
        fun refused(receive: String, change: String, net: Network = Network.TESTNET4) = try {
            Account.fromDescriptors(receive, change, net)
            fail("accepted: $receive")
        } catch (_: DescriptorException) {
        }
        refused("$r#aaaaaaaa", c) // bad checksum
        refused(r, r) // two receive chains
        refused(c, r) // swapped
        refused(r, descriptors("p2tr").second) // different accounts
        refused(r.replace("84'", "44'"), c.replace("84'", "44'")) // a path the vault never signs
        refused(r.replace("/84'/1'/0'", "/84'/0'/0'"), c.replace("/84'/1'/0'", "/84'/0'/0'")) // coin type of another network
        refused(r, c, Network.MAINNET) // test key on mainnet (coin type 1 and tpub)
        refused("pkh(${r.substringAfter("(")}", c) // other script types
        refused(r.replace("/0/*", "/0/5"), c) // not a wildcard chain
        refused(r.replace(Regex("tpub[1-9A-Za-z]+"), "tpubnotakey"), c)
        // A BIP84 key where a taproot account is named, and a mainnet key in a test wallet.
        refused("tr(${r.substringAfter("(")}", "tr(${c.substringAfter("(")}")
        assertEquals(2, w.accounts.size)
    }

    @Test
    fun a_wallet_needs_consistent_accounts() {
        val regtest = Vectors.wallets.getValue("regtest")
        val mainnet = Vectors.wallets.getValue("mainnet")
        try {
            Wallet(Network.REGTEST, listOf(regtest.account(AccountType.P2TR), mainnet.account(AccountType.P2WPKH)))
            fail("accepted")
        } catch (_: IllegalArgumentException) {
        }
    }
}

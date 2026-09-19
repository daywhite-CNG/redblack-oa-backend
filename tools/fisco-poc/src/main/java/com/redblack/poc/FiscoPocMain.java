/** 在独立进程中验证 Java 21 对 FISCO BCOS 的连接、部署、读写和交易回执。 */
package com.redblack.poc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.fisco.bcos.sdk.v3.BcosSDK;
import org.fisco.bcos.sdk.v3.client.Client;
import org.fisco.bcos.sdk.v3.model.TransactionReceipt;
import org.fisco.bcos.sdk.v3.transaction.manager.TransactionProcessorFactory;
import org.fisco.bcos.sdk.v3.transaction.model.dto.CallResponse;
import org.fisco.bcos.sdk.v3.transaction.model.dto.TransactionResponse;

public final class FiscoPocMain {
    private FiscoPocMain() {}

    /** 每次使用新证据标识和新合约，核对写入结果与可查询的链上回执。 */
    public static void main(String[] args) {
        Client client = null;
        try {
            if (args.length != 1) throw new IllegalArgumentException("需要 config.toml 的绝对路径");
            System.out.println("JAVA_VERSION=" + System.getProperty("java.version"));
            System.out.println("SDK_VERSION=3.8.0");
            System.out.println("JNI_VERSION=3.7.0");
            System.out.println("SOLIDITY_VERSION=0.8.11");
            String name = "contracts_EvidenceRegistry_sol_EvidenceRegistry";
            String abi = Files.readString(Path.of("compiled", name + ".abi")).trim();
            String bin = Files.readString(Path.of("compiled", name + ".bin")).trim();
            BcosSDK sdk = BcosSDK.build(args[0]);
            client = sdk.getClient("group0");
            var initial = client.getBlockNumber().getBlockNumber();
            System.out.println("INITIAL_BLOCK_NUMBER=" + initial);
            System.out.println("CHAIN_COMPATIBILITY_VERSION=" + client.getChainCompatibilityVersion());
            System.out.println("CLIENT_GROUP=" + client.getGroup());
            System.out.println("CLIENT_CHAIN_ID=" + client.getChainId());
            var processor = TransactionProcessorFactory.createAssembleTransactionProcessor(
                    client, client.getCryptoSuite().getCryptoKeyPair());
            TransactionResponse deployed = processor.deployAndGetResponse(abi, bin, List.of());
            TransactionReceipt deployReceipt = requireReceipt(deployed.getTransactionReceipt(), "deploy");
            String address = deployReceipt.getContractAddress();
            require(!isBlank(address), "合约地址为空");
            System.out.println("CONTRACT_ADDRESS=" + address);
            System.out.println("DEPLOY_TRANSACTION_HASH=" + deployReceipt.getTransactionHash());
            System.out.println("DEPLOY_BLOCK_NUMBER=" + deployReceipt.getBlockNumber());
            String id = sha256("poc:" + UUID.randomUUID());
            String hash = sha256("evidence:" + UUID.randomUUID());
            System.out.println("EVIDENCE_ID=" + id);
            System.out.println("EVIDENCE_HASH=" + hash);
            TransactionResponse written = processor.sendTransactionAndGetResponse(
                    address, abi, "registerEvidence", List.of(id, hash));
            TransactionReceipt writeReceipt = requireReceipt(written.getTransactionReceipt(), "write");
            System.out.println("WRITE_TRANSACTION_HASH=" + writeReceipt.getTransactionHash());
            System.out.println("WRITE_BLOCK_NUMBER=" + writeReceipt.getBlockNumber());
            require(client.getTransactionReceipt(writeReceipt.getTransactionHash(), false).getTransactionReceipt() != null,
                    "按交易哈希查不到回执");
            CallResponse queried = processor.sendCall(
                    client.getCryptoSuite().getCryptoKeyPair().getAddress(), address, abi,
                    "getEvidence", List.of(id));
            require(queried.getReturnObject() != null && queried.getReturnObject().size() == 1,
                    "查询未返回单个证据: " + queried);
            String actual = queried.getReturnObject().getFirst().toString();
            require(hash.equals(actual), "链上查询不匹配: " + actual);
            System.out.println("QUERIED_EVIDENCE=" + actual);
            var end = client.getBlockNumber().getBlockNumber();
            require(end.compareTo(writeReceipt.getBlockNumber()) >= 0, "最终链高小于写入区块");
            System.out.println("FINAL_BLOCK_NUMBER=" + end);
            System.out.println("RESULT=PASS");
        } catch (Throwable failure) {
            System.err.println("RESULT=FAIL");
            failure.printStackTrace(System.err);
            throw new IllegalStateException("FISCO POC 失败", failure);
        } finally {
            if (client != null) {
                client.stop();
                client.destroy();
            }
        }
    }

    private static TransactionReceipt requireReceipt(TransactionReceipt receipt, String stage) {
        require(receipt != null, stage + " 回执为空");
        require(receipt.isStatusOK(), stage + " 状态失败: " + receipt.getStatus() + " " + receipt.getMessage());
        require(!isBlank(receipt.getTransactionHash()) && receipt.getBlockNumber() != null,
                stage + " 缺交易哈希或区块号");
        return receipt;
    }

    private static String sha256(String value) throws Exception {
        return "0x" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private static boolean isBlank(String value) { return value == null || value.isBlank(); }
    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}

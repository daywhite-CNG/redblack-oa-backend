// SPDX-License-Identifier: MIT
pragma solidity 0.8.11;

/** 仅保存不透明证据标识与哈希，不保存 OA 业务明文。 */
contract EvidenceRegistry {
    mapping(string => string) private evidence;

    event EvidenceRegistered(string evidenceId, string evidenceHash);

    /** 首次写入证据；拒绝空值和覆盖。 */
    function registerEvidence(string calldata evidenceId, string calldata evidenceHash) external {
        require(bytes(evidenceId).length != 0 && bytes(evidenceHash).length != 0, "EMPTY_EVIDENCE");
        require(bytes(evidence[evidenceId]).length == 0, "EVIDENCE_ALREADY_EXISTS");
        evidence[evidenceId] = evidenceHash;
        emit EvidenceRegistered(evidenceId, evidenceHash);
    }

    /** 查询指定证据；不存在时明确报错。 */
    function getEvidence(string calldata evidenceId) external view returns (string memory) {
        require(bytes(evidence[evidenceId]).length != 0, "EVIDENCE_NOT_FOUND");
        return evidence[evidenceId];
    }
}

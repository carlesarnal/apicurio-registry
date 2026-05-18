import { FunctionComponent, useEffect, useState } from "react";
import "./ArtifactContractTabContent.css";
import {
    Card,
    CardBody,
    CardTitle,
    DescriptionList,
    DescriptionListDescription,
    DescriptionListGroup,
    DescriptionListTerm,
    Divider,
    EmptyState,
    EmptyStateBody,
    Grid,
    GridItem,
    Timestamp,
    TimestampFormat,
} from "@patternfly/react-core";
import { Table, Tbody, Td, Th, Thead, Tr } from "@patternfly/react-table";
import { ArtifactMetaData } from "@sdk/lib/generated-client/models";
import { ContractStatusBadge, QualityScoreGauge } from "@app/components/contracts";
import {
    ContractAuditEntry,
    ContractMetadata,
    ContractsService,
    QualityScore,
    useContractsService
} from "@services/useContractsService.ts";

export type ArtifactContractTabContentProps = {
    artifact: ArtifactMetaData;
};

export const ArtifactContractTabContent: FunctionComponent<ArtifactContractTabContentProps> = (props: ArtifactContractTabContentProps) => {

    const [contractMetadata, setContractMetadata] = useState<ContractMetadata>();
    const [qualityScore, setQualityScore] = useState<QualityScore>();
    const [auditLog, setAuditLog] = useState<ContractAuditEntry[]>([]);
    const [hasContract, setHasContract] = useState<boolean>(false);

    const contracts: ContractsService = useContractsService();

    useEffect(() => {
        const groupId = props.artifact?.groupId || null;
        const artifactId = props.artifact?.artifactId;
        if (!artifactId) return;

        contracts.getContractMetadata(groupId, artifactId).then(metadata => {
            setContractMetadata(metadata);
            setHasContract(!!metadata.status);
        }).catch(() => {
            setHasContract(false);
        });

        contracts.getContractQuality(groupId, artifactId, "default").then(score => {
            setQualityScore(score);
        }).catch(() => { /* no quality score available */ });

        contracts.getContractAuditLog(groupId, artifactId, 0, 10).then(entries => {
            setAuditLog(entries);
        }).catch(() => { /* no audit log available */ });
    }, [props.artifact]);

    if (!hasContract) {
        return (
            <div className="artifact-contract-tab-content">
                <EmptyState>
                    <EmptyStateBody>
                        No contract metadata found for this artifact. Submit an ODCS contract
                        or set contract metadata via the API to enable contract management.
                    </EmptyStateBody>
                </EmptyState>
            </div>
        );
    }

    return (
        <div className="artifact-contract-tab-content">
            <Grid hasGutter>
                <GridItem span={6}>
                    <Card className="contract-section" variant="secondary" style={{ backgroundColor: "white" }}>
                        <CardTitle>Contract Metadata</CardTitle>
                        <Divider />
                        <CardBody>
                            <DescriptionList isHorizontal>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Status</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        <ContractStatusBadge status={contractMetadata?.status} />
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Stage</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {contractMetadata?.stage || "-"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Owner Team</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {contractMetadata?.ownerTeam || "-"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Owner Domain</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {contractMetadata?.ownerDomain || "-"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Classification</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {contractMetadata?.classification || "-"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Support Contact</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {contractMetadata?.supportContact || "-"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                                <DescriptionListGroup>
                                    <DescriptionListTerm>Compatibility Group</DescriptionListTerm>
                                    <DescriptionListDescription>
                                        {contractMetadata?.compatibilityGroup || "-"}
                                    </DescriptionListDescription>
                                </DescriptionListGroup>
                            </DescriptionList>
                        </CardBody>
                    </Card>
                </GridItem>

                <GridItem span={6}>
                    <Card className="contract-section" variant="secondary" style={{ backgroundColor: "white" }}>
                        <CardTitle>Quality Score</CardTitle>
                        <Divider />
                        <CardBody>
                            {qualityScore ? (
                                <div className="quality-scores">
                                    <QualityScoreGauge label="Overall" score={qualityScore.overall} />
                                    <QualityScoreGauge label="Completeness" score={qualityScore.completeness} />
                                    <QualityScoreGauge label="Compliance" score={qualityScore.compliance} />
                                    <QualityScoreGauge label="Stability" score={qualityScore.stability} />
                                </div>
                            ) : (
                                <p>Quality score not available.</p>
                            )}
                        </CardBody>
                    </Card>
                </GridItem>

                <GridItem span={12}>
                    <Card className="contract-section" variant="secondary" style={{ backgroundColor: "white" }}>
                        <CardTitle>Audit Log</CardTitle>
                        <Divider />
                        <CardBody>
                            {auditLog.length > 0 ? (
                                <Table aria-label="Contract audit log" variant="compact" className="audit-table">
                                    <Thead>
                                        <Tr>
                                            <Th>Action</Th>
                                            <Th>Principal</Th>
                                            <Th>Details</Th>
                                            <Th>Date</Th>
                                        </Tr>
                                    </Thead>
                                    <Tbody>
                                        {auditLog.map((entry) => (
                                            <Tr key={entry.auditId}>
                                                <Td>{entry.action}</Td>
                                                <Td>{entry.principal || "-"}</Td>
                                                <Td>{entry.details || "-"}</Td>
                                                <Td>
                                                    {entry.createdOn ? (
                                                        <Timestamp
                                                            date={new Date(entry.createdOn)}
                                                            dateFormat={TimestampFormat.long}
                                                        />
                                                    ) : "-"}
                                                </Td>
                                            </Tr>
                                        ))}
                                    </Tbody>
                                </Table>
                            ) : (
                                <p>No audit entries yet.</p>
                            )}
                        </CardBody>
                    </Card>
                </GridItem>
            </Grid>
        </div>
    );
};

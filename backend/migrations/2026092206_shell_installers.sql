ALTER TABLE upload_jobs ADD COLUMN distribution_mode TEXT NOT NULL DEFAULT 'normal' CHECK(distribution_mode IN ('normal','paravoid'));
CREATE TABLE paravoid_installers (
    package_name TEXT NOT NULL,
    installer_version INTEGER NOT NULL,
    contract_id TEXT NOT NULL,
    signer_sha256 TEXT NOT NULL,
    PRIMARY KEY(package_name,installer_version),
    FOREIGN KEY(package_name,installer_version) REFERENCES app_versions(package_name,version_code),
    FOREIGN KEY(package_name,contract_id) REFERENCES paravoid_contracts(package_name,contract_id)
);
INSERT INTO paravoid_installers(package_name,installer_version,contract_id,signer_sha256)
SELECT package_name,installer_version,contract_id,'' FROM paravoid_contracts;
CREATE TRIGGER paravoid_first_installer AFTER INSERT ON paravoid_contracts
BEGIN
    INSERT INTO paravoid_installers(package_name,installer_version,contract_id,signer_sha256)
    VALUES (NEW.package_name,NEW.installer_version,NEW.contract_id,'');
END;

-- Catalog subject identifiers belong to one issuer. Changing a provider requires
-- an explicit identity migration rather than inheriting grants by matching sub.
CREATE TABLE deployment_identity (
    singleton INTEGER PRIMARY KEY CHECK(singleton = 1),
    issuer TEXT NOT NULL
);

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use ring::{
    rand::SystemRandom,
    signature::{EcdsaKeyPair, KeyPair, ECDSA_P256_SHA256_FIXED_SIGNING},
};
use serde_json::json;
pub struct Sender {
    pub pair: EcdsaKeyPair,
    pub key: String,
}
impl Sender {
    pub fn new() -> Self {
        let rng = SystemRandom::new();
        let doc = EcdsaKeyPair::generate_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, &rng).unwrap();
        let pair =
            EcdsaKeyPair::from_pkcs8(&ECDSA_P256_SHA256_FIXED_SIGNING, doc.as_ref(), &rng).unwrap();
        let key = URL_SAFE_NO_PAD.encode(pair.public_key().as_ref());
        Self { pair, key }
    }
    pub fn authorization(&self, origin: &str, exp: i64) -> String {
        let data = format!(
            "{}.{}",
            URL_SAFE_NO_PAD.encode(br#"{"alg":"ES256","typ":"JWT"}"#),
            URL_SAFE_NO_PAD.encode(
                json!({"aud":origin,"exp":exp,"sub":"mailto:test@example.com"}).to_string()
            )
        );
        let sig = self
            .pair
            .sign(&SystemRandom::new(), data.as_bytes())
            .unwrap();
        format!(
            "vapid t={data}.{}, k={}",
            URL_SAFE_NO_PAD.encode(sig.as_ref()),
            self.key
        )
    }
}

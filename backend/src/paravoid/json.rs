use super::{VerificationError as Error, MAX_INTEGER};
use serde::de::{self, DeserializeSeed, MapAccess, SeqAccess, Visitor};
use serde_json::{Map, Value};
use std::fmt;

struct Seed(usize);
impl<'de> DeserializeSeed<'de> for Seed {
    type Value = Value;
    fn deserialize<D: de::Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        if self.0 > 32 {
            return Err(de::Error::custom("nesting limit"));
        }
        deserializer.deserialize_any(self)
    }
}
impl<'de> Visitor<'de> for Seed {
    type Value = Value;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("strict Paravoid JSON")
    }
    fn visit_unit<E: de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_bool<E: de::Error>(self, v: bool) -> Result<Value, E> {
        Ok(Value::Bool(v))
    }
    fn visit_u64<E: de::Error>(self, v: u64) -> Result<Value, E> {
        if v > MAX_INTEGER {
            return Err(E::custom("integer range"));
        }
        Ok(Value::from(v))
    }
    // No signed-integer or float visitors: this also rejects -0 and 1e0.
    fn visit_str<E: de::Error>(self, v: &str) -> Result<Value, E> {
        Ok(Value::String(v.into()))
    }
    fn visit_string<E: de::Error>(self, v: String) -> Result<Value, E> {
        Ok(Value::String(v))
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<Value, A::Error> {
        let mut out = Vec::new();
        while let Some(value) = seq.next_element_seed(Seed(self.0 + 1))? {
            out.push(value);
        }
        Ok(Value::Array(out))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut map: A) -> Result<Value, A::Error> {
        let mut out = Map::new();
        while let Some(key) = map.next_key::<String>()? {
            if out.contains_key(&key) {
                return Err(de::Error::custom("duplicate key"));
            }
            out.insert(key, map.next_value_seed(Seed(self.0 + 1))?);
        }
        Ok(Value::Object(out))
    }
}

pub fn parse_json(bytes: &[u8], limit: usize) -> Result<Value, Error> {
    if bytes.len() > limit {
        return Err(Error::LimitExceeded);
    }
    let mut decoder = serde_json::Deserializer::from_slice(bytes);
    let value = Seed(0).deserialize(&mut decoder).map_err(|error| {
        if error.to_string().starts_with("nesting limit") {
            Error::LimitExceeded
        } else {
            Error::Malformed
        }
    })?;
    decoder.end().map_err(|_| Error::Malformed)?;
    Ok(value)
}

pub(super) fn ordinary_strings(value: &Value) -> Result<(), Error> {
    match value {
        Value::String(s) if s.len() > 4096 => return Err(Error::LimitExceeded),
        Value::Array(a) => {
            for v in a {
                ordinary_strings(v)?;
            }
        }
        Value::Object(o) => {
            for (k, v) in o {
                if k.len() > 4096 {
                    return Err(Error::LimitExceeded);
                }
                ordinary_strings(v)?;
            }
        }
        _ => {}
    }
    Ok(())
}

/// RFC 8785 writer for the selected safe nonnegative integer subset. Verification
/// always uses original body bytes; this writer is only for creating new objects.
pub fn canonical_json(value: &Value) -> Result<Vec<u8>, Error> {
    fn write(value: &Value, depth: usize, out: &mut Vec<u8>) -> Result<(), Error> {
        if depth > 32 {
            return Err(Error::LimitExceeded);
        }
        match value {
            Value::Object(o) => {
                let mut fields: Vec<_> = o.iter().collect();
                fields.sort_by(|a, b| a.0.encode_utf16().cmp(b.0.encode_utf16()));
                out.push(b'{');
                for (i, (key, value)) in fields.into_iter().enumerate() {
                    if i != 0 {
                        out.push(b',');
                    }
                    serde_json::to_writer(&mut *out, key).map_err(|_| Error::Malformed)?;
                    out.push(b':');
                    write(value, depth + 1, out)?;
                }
                out.push(b'}');
            }
            Value::Array(a) => {
                out.push(b'[');
                for (i, value) in a.iter().enumerate() {
                    if i != 0 {
                        out.push(b',');
                    }
                    write(value, depth + 1, out)?;
                }
                out.push(b']');
            }
            Value::Number(n) if n.as_u64().is_none_or(|n| n > MAX_INTEGER) => {
                return Err(Error::Malformed)
            }
            _ => serde_json::to_writer(out, value).map_err(|_| Error::Malformed)?,
        }
        Ok(())
    }
    let mut out = Vec::new();
    write(value, 0, &mut out)?;
    Ok(out)
}

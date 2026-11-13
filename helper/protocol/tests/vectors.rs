//! vectors.rs: checks the Rust codec against the shared golden vectors in protocol/test-vectors.txt.

use padlink_protocol::{decode_frame, encode, from_hex, to_hex, Frame, MouseFrame, PenFrame, TOKEN_LEN};
use std::collections::HashMap;

const VECTORS: &str = include_str!("../../../protocol/test-vectors.txt");

/// Parses every non-comment line into its key/value fields.
fn vectors() -> Vec<HashMap<String, String>> {
    VECTORS
        .lines()
        .map(str::trim)
        .filter(|l| !l.is_empty() && !l.starts_with('#'))
        .map(|line| {
            line.split(' ')
                .map(|part| {
                    let (k, v) = part.split_once('=').expect("key=value");
                    (k.to_owned(), v.to_owned())
                })
                .collect()
        })
        .collect()
}

/// Reads a numeric field.
fn num<T: std::str::FromStr>(v: &HashMap<String, String>, key: &str) -> T
where
    T::Err: std::fmt::Debug,
{
    v[key].parse().unwrap_or_else(|e| panic!("{key}: {e:?}"))
}

/// Builds the frame a vector describes.
fn frame_of(v: &HashMap<String, String>) -> Frame {
    match v["frame"].as_str() {
        "hello" => {
            let mut token = [0u8; TOKEN_LEN];
            token.copy_from_slice(&from_hex(&v["token"]).unwrap());
            Frame::Hello { version: num(v, "version"), token, name: v["name"].clone() }
        }
        "welcome" => Frame::Welcome { version: num(v, "version"), status: num(v, "status") },
        "pair_code" => Frame::PairCode { code: num(v, "code") },
        "host_info" => Frame::HostInfo { width: num(v, "width"), height: num(v, "height") },
        "mouse" => Frame::Mouse(MouseFrame {
            buttons: num(v, "buttons"),
            dx: num(v, "dx"),
            dy: num(v, "dy"),
            wheel: num(v, "wheel"),
            pan: num(v, "pan"),
        }),
        "pen" => Frame::Pen(PenFrame {
            flags: num(v, "flags"),
            x: num(v, "x"),
            y: num(v, "y"),
            pressure: num(v, "pressure"),
            tilt_x: num(v, "tilt_x"),
            tilt_y: num(v, "tilt_y"),
            twist: num(v, "twist"),
        }),
        "ping" => Frame::Ping { nonce: num(v, "nonce") },
        "pong" => Frame::Pong { nonce: num(v, "nonce") },
        "bye" => Frame::Bye,
        other => panic!("unknown vector {other}"),
    }
}

/// Every vector encodes to its hex.
#[test]
fn encodes_golden_vectors() {
    let all = vectors();
    assert!(all.len() >= 10);
    for v in &all {
        assert_eq!(to_hex(&encode(&frame_of(v))), v["hex"], "{v:?}");
    }
}

/// Every vector's hex decodes back to the described frame.
#[test]
fn decodes_golden_vectors() {
    for v in &vectors() {
        let bytes = from_hex(&v["hex"]).unwrap();
        assert_eq!(decode_frame(&bytes).unwrap(), frame_of(v), "{v:?}");
    }
}

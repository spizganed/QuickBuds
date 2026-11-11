//! Minimal JSON reader for the `F4` ack: checks that the text is valid JSON and returns the top-level
//! `"cmd"` string. No dependencies.

const MAX_DEPTH: usize = 128;

/// `None`: not valid JSON. `Some(None)`: valid, but no top-level `"cmd"` string.
/// A repeated key counts last. Any other type under `"cmd"` counts as no string.
pub fn cmd_of(json: &[u8]) -> Option<Option<String>> {
    let mut p = Parser { s: std::str::from_utf8(json).ok()?.as_bytes(), i: 0 };
    p.ws();
    let cmd = if p.peek() == Some(b'{') { p.object(0)? } else { p.value(0)?; None };
    p.ws();
    (p.i == p.s.len()).then_some(cmd)
}

/// The reply text `{"cmd":"<c>"}`, escaped as the shipping JSON writer does.
pub fn echo(cmd: &str) -> String {
    let mut s = String::from("{\"cmd\":\"");
    for c in cmd.chars() {
        match c {
            '"' => s.push_str("\\\""),
            '\\' => s.push_str("\\\\"),
            '\n' => s.push_str("\\n"),
            '\r' => s.push_str("\\r"),
            '\t' => s.push_str("\\t"),
            '\u{8}' => s.push_str("\\b"),
            '\u{c}' => s.push_str("\\f"),
            c if (c as u32) < 0x20 => s.push_str(&format!("\\u{:04x}", c as u32)),
            c => s.push(c),
        }
    }
    s.push_str("\"}");
    s
}

struct Parser<'a> { s: &'a [u8], i: usize }

impl Parser<'_> {
    fn peek(&self) -> Option<u8> { self.s.get(self.i).copied() }
    fn eat(&mut self, b: u8) -> Option<()> { (self.peek() == Some(b)).then(|| self.i += 1) }
    fn ws(&mut self) { while matches!(self.peek(), Some(b' ' | b'\t' | b'\n' | b'\r')) { self.i += 1; } }
    fn word(&mut self, w: &str) -> Option<()> {
        self.s[self.i..].starts_with(w.as_bytes()).then(|| self.i += w.len())
    }

    /// Reads one value. Returns it when it is a string.
    fn value(&mut self, depth: usize) -> Option<Option<String>> {
        if depth > MAX_DEPTH { return None; }
        match self.peek()? {
            b'{' => self.object(depth).map(|_| None),
            b'[' => {
                self.i += 1;
                self.ws();
                if self.eat(b']').is_none() {
                    loop {
                        self.ws();
                        self.value(depth + 1)?;
                        self.ws();
                        if self.eat(b',').is_none() { self.eat(b']')?; break; }
                    }
                }
                Some(None)
            }
            b'"' => self.string().map(Some),
            b't' => self.word("true").map(|_| None),
            b'f' => self.word("false").map(|_| None),
            b'n' => self.word("null").map(|_| None),
            _ => self.number().map(|_| None),
        }
    }

    /// Reads one object. Returns the string under its `"cmd"` key.
    fn object(&mut self, depth: usize) -> Option<Option<String>> {
        if depth > MAX_DEPTH { return None; }
        self.eat(b'{')?;
        self.ws();
        let mut cmd = None;
        if self.eat(b'}').is_none() {
            loop {
                self.ws();
                let key = self.string()?;
                self.ws();
                self.eat(b':')?;
                self.ws();
                let v = self.value(depth + 1)?;
                if key == "cmd" { cmd = v; }
                self.ws();
                if self.eat(b',').is_none() { self.eat(b'}')?; break; }
            }
        }
        Some(cmd)
    }

    fn number(&mut self) -> Option<()> {
        self.eat(b'-');
        match self.peek()? {
            b'0' => self.i += 1,
            b'1'..=b'9' => self.digits(),
            _ => return None,
        }
        if self.eat(b'.').is_some() { self.digit()?; self.digits(); }
        if matches!(self.peek(), Some(b'e' | b'E')) {
            self.i += 1;
            if matches!(self.peek(), Some(b'+' | b'-')) { self.i += 1; }
            self.digit()?;
            self.digits();
        }
        Some(())
    }
    fn digit(&mut self) -> Option<()> { self.peek()?.is_ascii_digit().then(|| self.i += 1) }
    fn digits(&mut self) { while self.peek().is_some_and(|b| b.is_ascii_digit()) { self.i += 1; } }

    fn hex4(&mut self) -> Option<u32> {
        let h = self.s.get(self.i..self.i + 4)?;
        if !h.iter().all(u8::is_ascii_hexdigit) { return None; }
        self.i += 4;
        u32::from_str_radix(std::str::from_utf8(h).ok()?, 16).ok()
    }

    fn string(&mut self) -> Option<String> {
        self.eat(b'"')?;
        let mut out = Vec::new();
        loop {
            let b = self.peek()?;
            self.i += 1;
            match b {
                b'"' => return String::from_utf8(out).ok(),
                0..=0x1F => return None,
                b'\\' => {
                    let e = self.peek()?;
                    self.i += 1;
                    let c = match e {
                        b'"' | b'\\' | b'/' => e as char,
                        b'b' => '\u{8}',
                        b'f' => '\u{c}',
                        b'n' => '\n',
                        b'r' => '\r',
                        b't' => '\t',
                        b'u' => {
                            let hi = self.hex4()?;
                            let cp = if (0xD800..0xDC00).contains(&hi) {
                                self.word("\\u")?;
                                let lo = self.hex4().filter(|l| (0xDC00..0xE000).contains(l))?;
                                0x10000 + ((hi - 0xD800) << 10) + (lo - 0xDC00)
                            } else { hi };
                            char::from_u32(cp)?
                        }
                        _ => return None,
                    };
                    out.extend_from_slice(c.encode_utf8(&mut [0; 4]).as_bytes());
                }
                _ => out.push(b),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn cmd(s: &str) -> Option<Option<String>> { cmd_of(s.as_bytes()) }

    #[test]
    fn finds_the_top_level_cmd() {
        assert_eq!(cmd(r#"{"cmd":"x"}"#), Some(Some("x".into())));
        assert_eq!(cmd(r#" { "a" : [1, {"cmd":"in"}], "cmd" : "top" , "b":null } "#), Some(Some("top".into())));
        assert_eq!(cmd(r#"{"cmd":"a","cmd":"b"}"#), Some(Some("b".into())));
        assert_eq!(cmd(r#"{"cmd":"a","cmd":5}"#), Some(None));
    }

    #[test]
    fn valid_without_a_cmd_string() {
        for s in ["{}", r#"{"cmd":5}"#, r#"{"a":{"cmd":"in"}}"#, "[1,2]", r#""cmd""#, "null", "-0.5e+3", "true"] {
            assert_eq!(cmd(s), Some(None), "{s}");
        }
    }

    #[test]
    fn escapes_round_trip() {
        assert_eq!(cmd(r#"{"cmd":"a\"b\\c\n\u00e9\ud83d\ude00"}"#), Some(Some("a\"b\\c\n\u{e9}\u{1F600}".into())));
        assert_eq!(echo("a\"b\\c\n\u{1}\u{e9}"), "{\"cmd\":\"a\\\"b\\\\c\\n\\u0001\u{e9}\"}");
    }

    #[test]
    fn invalid_json() {
        for s in [
            "", "{", "}", "{\"cmd\"}", "{\"cmd\":}", "{\"cmd\":\"x\"", "{\"cmd\":\"x\"}x", "{\"cmd\":\"x\",}", "[1,]",
            "{'cmd':'x'}", "01", "1.", "-", "tru", "\"a\nb\"", "\"\\x\"", "\"\\u12\"", "\"\\ud800\"", "\"\\ud800\\u0041\"",
            "\"\\udc00\"",
        ] {
            assert_eq!(cmd(s), None, "{s:?}");
        }
        assert_eq!(cmd_of(&[b'"', 0xFF, b'"']), None);
        assert_eq!(cmd(&"[".repeat(200)), None);
    }
}

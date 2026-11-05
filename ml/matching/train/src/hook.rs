//! Publishes training steps for `calibrate watch`, using its hook protocol v1: newline-delimited
//! JSON on a Unix socket at `$CALIBRATE_HOOK_DIR/calibrate-hook-<pid>.sock`. Calibrate's documented
//! contract is that a hook can never break training, so every error here is ignored.

use std::collections::VecDeque;
use std::io::Write;
use std::os::unix::fs::PermissionsExt;
use std::os::unix::net::{UnixListener, UnixStream};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};
use std::time::{SystemTime, UNIX_EPOCH};

/// A watcher attaching mid-run still sees recent steps straight away.
const REPLAY: usize = 64;

#[derive(Default)]
struct Shared {
    watchers: Vec<UnixStream>,
    recent: VecDeque<String>,
}

pub struct Hook {
    shared: Arc<Mutex<Shared>>,
    path: Option<PathBuf>,
    pid: u32,
    step: u64,
}

/// One training step's timings, in seconds.
#[derive(Debug, Clone, Copy)]
pub struct Step {
    pub total: f64,
    pub data: f64,
    pub compute: f64,
    pub optimizer: f64,
    pub tokens: u64,
    pub samples: u64,
    pub flops: f64,
}

fn hello_line(pid: u32, device: &str) -> String {
    format!(
        "{{\"v\":1,\"type\":\"hello\",\"pid\":{pid},\"rank\":0,\"world_size\":1,\"framework\":\"burn\",\"device\":\"{device}\",\"sync\":\"phases\",\"hook_version\":\"saarthi-0.1\"}}\n"
    )
}

fn step_line(pid: u32, number: u64, end_ms: u128, s: &Step) -> String {
    format!(
        "{{\"v\":1,\"type\":\"step\",\"pid\":{pid},\"rank\":0,\"step\":{number},\"t_end_ms\":{end_ms},\"step_s\":{:.6},\"data_s\":{:.6},\"compute_s\":{:.6},\"optimizer_s\":{:.6},\"tokens\":{},\"samples\":{},\"flops\":{:e}}}\n",
        s.total, s.data, s.compute, s.optimizer, s.tokens, s.samples, s.flops
    )
}

impl Hook {
    pub fn start(device: &str) -> Hook {
        let pid = std::process::id();
        let shared = Arc::new(Mutex::new(Shared::default()));
        let dir = std::env::var_os("CALIBRATE_HOOK_DIR").map_or_else(|| PathBuf::from("/tmp"), PathBuf::from);
        let path = dir.join(format!("calibrate-hook-{pid}.sock"));
        let _ = std::fs::remove_file(&path);
        let Ok(listener) = UnixListener::bind(&path) else {
            return Hook { shared, path: None, pid, step: 0 };
        };
        let _ = std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600));

        let hello = hello_line(pid, device);
        let accepted = Arc::clone(&shared);
        std::thread::spawn(move || {
            for stream in listener.incoming().flatten() {
                let Ok(mut state) = accepted.lock() else { return };
                let mut stream = stream;
                if stream.set_nonblocking(true).is_err() || stream.write_all(hello.as_bytes()).is_err() {
                    continue;
                }
                let caught_up = state.recent.iter().all(|line| stream.write_all(line.as_bytes()).is_ok());
                if caught_up {
                    state.watchers.push(stream);
                }
            }
        });
        Hook { shared, path: Some(path), pid, step: 0 }
    }

    pub fn publish(&mut self, step: Step) {
        self.step += 1;
        let end_ms = SystemTime::now().duration_since(UNIX_EPOCH).map_or(0, |d| d.as_millis());
        let line = step_line(self.pid, self.step, end_ms, &step);
        if let Ok(mut state) = self.shared.lock() {
            // A watcher that cannot keep up is dropped rather than ever slowing training.
            state.watchers.retain_mut(|watcher| watcher.write_all(line.as_bytes()).is_ok());
            state.recent.push_back(line);
            if state.recent.len() > REPLAY {
                state.recent.pop_front();
            }
        }
    }
}

impl Drop for Hook {
    fn drop(&mut self) {
        if let Some(path) = &self.path {
            let _ = std::fs::remove_file(path);
        }
    }
}

/// FLOPs per token for training a transformer: `6N + 12·L·H·S`, the PaLM formula Calibrate's own hook uses.
pub fn flops_per_token(params: usize, layers: usize, hidden: usize, seq_len: usize) -> f64 {
    6.0 * params as f64 + 12.0 * (layers * hidden * seq_len) as f64
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{BufRead, BufReader};

    #[test]
    fn lines_follow_the_protocol() {
        let hello = hello_line(42, "metal");
        assert!(hello.starts_with("{\"v\":1,\"type\":\"hello\",\"pid\":42,") && hello.ends_with("}\n"));
        let step = Step { total: 0.5, data: 0.01, compute: 0.4, optimizer: 0.09, tokens: 1024, samples: 32, flops: 3.1e11 };
        let line = step_line(42, 7, 1_760_000_000_000, &step);
        for field in ["\"step\":7", "\"t_end_ms\":1760000000000", "\"step_s\":0.500000", "\"data_s\":0.010000", "\"tokens\":1024", "\"samples\":32", "\"flops\":3.1e11"] {
            assert!(line.contains(field), "{field} missing from {line}");
        }
    }

    #[test]
    fn matches_the_published_formula_at_model_size() {
        assert_eq!(flops_per_token(22_700_000, 6, 384, 48), 136_200_000.0 + 1_327_104.0);
    }

    #[test]
    fn a_watcher_gets_hello_then_recent_steps_and_the_socket_goes_away() {
        let dir = std::env::temp_dir().join(format!("saarthi-hook-{}", std::process::id()));
        std::fs::create_dir_all(&dir).unwrap();
        std::env::set_var("CALIBRATE_HOOK_DIR", &dir);
        let mut hook = Hook::start("metal");
        hook.publish(Step { total: 1.0, data: 0.0, compute: 1.0, optimizer: 0.0, tokens: 1, samples: 1, flops: 1.0 });
        let path = hook.path.clone().unwrap();
        let mode = std::fs::metadata(&path).unwrap().permissions().mode() & 0o777;
        assert_eq!(mode, 0o600);
        let stream = UnixStream::connect(&path).unwrap();
        let mut lines = BufReader::new(stream).lines();
        assert!(lines.next().unwrap().unwrap().contains("\"type\":\"hello\""));
        assert!(lines.next().unwrap().unwrap().contains("\"type\":\"step\""));
        drop(hook);
        assert!(!path.exists());
    }
}

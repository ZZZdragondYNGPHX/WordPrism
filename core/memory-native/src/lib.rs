use std::{collections::HashMap, sync::{Arc, Mutex, OnceLock}, time::Duration};
use fsrs::{FSRS, FSRSItem, MemoryState, CombinedProgressState, ComputeParametersInput, TrainingConfig};
use jni::{JNIEnv, objects::{JObject, JString}, sys::{jlong, jstring}};
use serde::Deserialize;
use serde_json::{Value, json};

type Progress = Arc<Mutex<CombinedProgressState>>;
static RUNNING: OnceLock<Mutex<HashMap<u64, Progress>>> = OnceLock::new();
fn running() -> &'static Mutex<HashMap<u64, Progress>> { RUNNING.get_or_init(Default::default) }

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct State { stability: f32, difficulty: f32 }
#[derive(Deserialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
enum Request {
    Defaults,
    Validate { weights: Vec<f32> },
    Schedule { weights: Vec<f32>, memory: Option<State>, #[serde(rename = "elapsedDays")] elapsed_days: u32, rating: u32, retention: f32 },
    Evaluate { weights: Vec<f32>, items: Vec<FSRSItem> },
    Fit { items: Vec<FSRSItem>, token: u64 },
}

fn validate_weights(weights: &[f32]) -> Result<(), String> {
    if weights.is_empty() { return Ok(()); }
    if weights.len() != 21 || weights.iter().any(|v| !v.is_finite()) { return Err("Expected 21 finite FSRS-6 weights".into()); }
    // Bounds from the pinned kernel's parameter_clipper.rs, reject rather than silently clipping.
    let bounds = [(0.001,100.0),(0.001,100.0),(0.001,100.0),(0.001,100.0),(1.0,10.0),(0.001,4.0),
        (0.001,4.0),(0.001,0.75),(0.0,4.5),(0.0,0.8),(0.001,3.5),(0.001,5.0),(0.001,0.25),
        (0.001,0.9),(0.0,4.0),(0.0,1.0),(1.0,6.0),(0.0,2.0),(0.0,2.0),(0.0,0.8),(0.1,0.8)];
    if weights.iter().zip(bounds).any(|(&w,(lo,hi))| w < lo || w > hi) { return Err("FSRS-6 weight outside supported bounds".into()); }
    Ok(())
}
fn validate_items(items: &[FSRSItem]) -> Result<(), String> {
    if items.is_empty() || items.len() > 5000 || items.iter().any(|i| i.reviews.len() < 2 || i.reviews.len() > 256 ||
        i.reviews[0].delta_t != 0 || i.reviews.iter().any(|r| !(1..=4).contains(&r.rating) || r.delta_t > 36500) ||
        i.reviews.last().unwrap().delta_t == 0) { return Err("Invalid or excessive review history".into()); }
    Ok(())
}
fn run(request: Request) -> Result<Value, String> {
    match request {
        Request::Defaults => Ok(json!(fsrs::DEFAULT_PARAMETERS)),
        Request::Validate { weights } => { validate_weights(&weights)?; Ok(json!(weights)) },
        Request::Schedule { weights, memory, elapsed_days, rating, retention } => {
            validate_weights(&weights)?;
            if !(1..=4).contains(&rating) || !(0.7..=0.97).contains(&retention) || elapsed_days > 36500 { return Err("Invalid scheduling input".into()); }
            if memory.as_ref().is_some_and(|m| !m.stability.is_finite() || m.stability <= 0.0 || !(1.0..=10.0).contains(&m.difficulty)) { return Err("Invalid memory state".into()); }
            let states = FSRS::new(&weights).map_err(|e| e.to_string())?.next_states(memory.map(|m| MemoryState { stability: m.stability, difficulty: m.difficulty }), retention, elapsed_days).map_err(|e| e.to_string())?;
            Ok(json!(match rating { 1 => states.again, 2 => states.hard, 3 => states.good, _ => states.easy }))
        },
        Request::Evaluate { weights, items } => {
            validate_weights(&weights)?; validate_items(&items)?;
            let result = FSRS::new(&weights).map_err(|e| e.to_string())?.evaluate(items, |_| true).map_err(|e| e.to_string())?;
            Ok(json!({"log_loss": result.log_loss, "rmse_bins": result.rmse_bins}))
        },
        Request::Fit { items, token } => {
            validate_items(&items)?;
            let progress = CombinedProgressState::new_shared();
            { let mut map = running().lock().unwrap(); if map.contains_key(&token) { return Err("Fit token already in use".into()); } map.insert(token, progress.clone()); }
            let (tx, rx) = std::sync::mpsc::channel::<()>();
            let deadline_progress = progress.clone();
            let watchdog = std::thread::spawn(move || {
                if rx.recv_timeout(Duration::from_secs(30)).is_err() { deadline_progress.lock().unwrap().want_abort = true; }
            });
            let result = rayon::ThreadPoolBuilder::new().num_threads(1).build().map_err(|e| e.to_string())?.install(||
                fsrs::compute_parameters(ComputeParametersInput {
                    train_set: items, progress: Some(progress),
                    training_config: Some(TrainingConfig { batch_size: 64, max_seq_len: 256, num_epochs: 5, ..Default::default() }),
                    ..Default::default()
                }).map_err(|e| e.to_string()));
            let _ = tx.send(()); let _ = watchdog.join(); running().lock().unwrap().remove(&token);
            result.map(|weights| json!(weights))
        }
    }
}
pub fn dispatch(input: &str) -> String {
    let result = std::panic::catch_unwind(|| {
        if input.len() > 16 * 1024 * 1024 { return Err("Native request too large".into()); }
        serde_json::from_str::<Request>(input).map_err(|_| "Malformed native request".to_string()).and_then(run)
    });
    match result { Ok(Ok(value)) => json!({"value":value}), Ok(Err(error)) => json!({"error":error}), Err(_) => json!({"error":"Memory kernel failed"}) }.to_string()
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wordprism_memory_NativeMemoryKernel_invokeNative(mut env: JNIEnv, _: JObject, request: JString) -> jstring {
    let result = match env.get_string(&request) { Ok(s) => dispatch(&String::from(s)), Err(_) => json!({"error":"Invalid JNI string"}).to_string() };
    env.new_string(result).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}
#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_wordprism_memory_NativeMemoryKernel_cancelNative(_: JNIEnv, _: JObject, token: jlong) {
    cancel(token as u64);
}
fn cancel(token: u64) {
    if let Some(p) = running().lock().unwrap().get(&token) { p.lock().unwrap().want_abort = true; }
}

#[cfg(test)] mod tests {
    use super::*;
    #[test] fn matches_upstream_reference_for_all_ratings() {
        let expected = FSRS::default().next_states(Some(MemoryState { stability: 7.0, difficulty: 5.0 }), 0.9, 7).unwrap();
        for (rating, outcome) in [expected.again,expected.hard,expected.good,expected.easy].into_iter().enumerate() {
            let output: Value = serde_json::from_str(&dispatch(&json!({"kind":"schedule","weights":[],"memory":{"stability":7.0,"difficulty":5.0},"elapsedDays":7,"rating":rating+1,"retention":0.9}).to_string())).unwrap();
            assert!((output["value"]["interval"].as_f64().unwrap() as f32 - outcome.interval).abs() < 1e-6);
            assert!((output["value"]["memory"]["stability"].as_f64().unwrap() as f32 - outcome.memory.stability).abs() < 1e-6);
            assert!((output["value"]["memory"]["difficulty"].as_f64().unwrap() as f32 - outcome.memory.difficulty).abs() < 1e-6);
        }
    }
    #[test] fn new_good_has_pinned_default_stability() {
        let result: Value = serde_json::from_str(&dispatch(r#"{"kind":"schedule","weights":[],"memory":null,"elapsedDays":0,"rating":3,"retention":0.9}"#)).unwrap();
        assert!((result["value"]["memory"]["stability"].as_f64().unwrap() - 2.3065).abs() < 0.00001);
    }
    #[test] fn rejects_invalid_inputs_without_unwinding_through_jni() {
        assert!(dispatch(r#"{"kind":"evaluate","weights":[],"items":[]}"#).contains("error"));
        assert!(dispatch(r#"{"kind":"validate","weights":[2,3]}"#).contains("error"));
    }
    #[test] fn fitting_observes_cancellation() {
        // Include first delayed-review prefixes: the upstream initializer requires them.
        let items: Vec<_> = (0..1600).flat_map(|card| {
            let reviews = vec![
                fsrs::FSRSReview {rating:3,delta_t:0}, fsrs::FSRSReview {rating:if card % 5 == 0 {1} else {3},delta_t:2},
                fsrs::FSRSReview {rating:3,delta_t:4}, fsrs::FSRSReview {rating:if card % 7 == 0 {1} else {4},delta_t:10},
            ];
            (2..=4).map(move |length| FSRSItem { reviews: reviews[..length].to_vec() })
        }).collect();
        let worker = std::thread::spawn(move || run(Request::Fit {items, token: 987654}));
        let mut registered = false;
        for _ in 0..1000 {
            let active = { running().lock().unwrap().contains_key(&987654) };
            if active { registered = true; cancel(987654); break; }
            std::thread::sleep(Duration::from_millis(1));
        }
        assert!(registered);
        assert!(worker.join().unwrap().is_err());
        assert!(!running().lock().unwrap().contains_key(&987654));
    }
}

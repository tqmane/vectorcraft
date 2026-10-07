//! JNI is isolated here; no UI or document engine needs unsafe code.
use super::*;
use jni::objects::{JObject, JString, JValue};
use std::cell::RefCell;
use std::sync::{Mutex, OnceLock};
pub use winit::platform::android::activity::AndroidApp;

static APP: OnceLock<AndroidApp> = OnceLock::new();
static CONTEXT: OnceLock<Mutex<Option<egui::Context>>> = OnceLock::new();
type DialogReply = (FileDialog, String, Vec<PathBuf>, std::time::Instant);
pub type Callback<A> = std::rc::Rc<dyn Fn(&mut A)>;
thread_local! {
    static COMMAND: RefCell<Option<(String, serde_json::Value)>> = const { RefCell::new(None) };
    static PENDING: RefCell<Option<Pending>> = const { RefCell::new(None) };
    static REPLY: RefCell<Option<DialogReply>> = const { RefCell::new(None) };
    static READY_WIDGET: RefCell<Option<egui::Id>> = const { RefCell::new(None) };
    static PEN_BUTTONS: RefCell<u8> = const { RefCell::new(0) };
    static CALLBACK: RefCell<Option<u64>> = const { RefCell::new(None) };
    static CALLBACKS: RefCell<std::collections::HashMap<u64, Box<dyn std::any::Any>>> = RefCell::new(std::collections::HashMap::new());
    static NEXT_CALLBACK: std::cell::Cell<u64> = const { std::cell::Cell::new(0) };
    static READY_CALLBACK: RefCell<Option<u64>> = const { RefCell::new(None) };
    static INCOMING: RefCell<Vec<PathBuf>> = const { RefCell::new(Vec::new()) };
}
struct Pending {
    id: String,
    command: Option<(String, serde_json::Value)>,
    callback: Option<u64>,
    widget: Option<egui::Id>,
    dialog: FileDialog,
    kind: String,
}

pub fn initialize(app: AndroidApp) -> Result<(), String> {
    android_logger::init_once(android_logger::Config::default().with_max_level(log::LevelFilter::Info).with_tag("Craft"));
    let dir = app.internal_data_path().ok_or("Android did not provide an application data directory")?;
    let workspace = dir.join("Documents/Workspace");
    std::fs::create_dir_all(&workspace).map_err(|e| e.to_string())?;
    std::env::set_current_dir(workspace).map_err(|e| e.to_string())?;
    APP.set(app).map_err(|_| "Android application already initialized".to_string())?;
    if call("bridgeVersion", "")? != "1" {
        return Err("The Android activity and native library versions do not match. Rebuild both APK ABIs.".into());
    }
    Ok(())
}

pub fn android_app() -> Option<AndroidApp> {
    APP.get().cloned()
}

/// Test-only CLI arguments, written through `adb run-as` into this debug application's sandbox.
/// Release builds never read these and no exported Intent can enable the control server.
pub fn arguments() -> Vec<String> {
    #[cfg(debug_assertions)]
    if let Some(root) = APP.get().and_then(|a| a.internal_data_path())
        && let Ok(bytes) = std::fs::read(root.join("android-test-args.json"))
        && bytes.len() <= 16 * 1024
        && let Ok(args) = serde_json::from_slice::<Vec<String>>(&bytes)
        && args.len() <= 32
    {
        return std::iter::once("craft-android".into()).chain(args).collect();
    }
    Vec::new()
}

pub fn configure_options(mut options: eframe::NativeOptions) -> eframe::NativeOptions {
    if let eframe::egui_wgpu::WgpuSetup::CreateNew(setup) = &mut options.wgpu_options.wgpu_setup {
        if std::env::var("WGPU_BACKEND").as_deref() == Ok("gl") {
            setup.instance_descriptor.backends = eframe::wgpu::Backends::GL;
        }
        let original = setup.device_descriptor.clone();
        setup.device_descriptor = std::sync::Arc::new(move |adapter| {
            let mut descriptor = original(adapter);
            // Desktop defaults require 64 KiB uniforms; Android's mobile baseline is 16 KiB.
            // Do not request every advertised maximum: software drivers may expose huge pools.
            let supported = adapter.limits();
            let baseline = if adapter.get_info().backend == eframe::wgpu::Backend::Gl {
                eframe::wgpu::Limits::downlevel_webgl2_defaults()
            } else {
                eframe::wgpu::Limits::downlevel_defaults()
            };
            descriptor.required_limits = baseline.using_resolution(supported.clone()).using_alignment(supported);
            descriptor
        });
    }
    options
}

#[allow(unsafe_code)] // JNI locates this callback by its exported symbol, without a Rust caller.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ai_craft_android_CraftActivity_wake(_env: jni::JNIEnv<'_>, _activity: JObject<'_>) {
    if let Some(slot) = CONTEXT.get()
        && let Some(ctx) = slot.lock().unwrap_or_else(std::sync::PoisonError::into_inner).as_ref()
    {
        ctx.request_repaint();
    }
}

/// Preserve Android's pen axes, eraser and side buttons before egui handles this frame.
/// Azimuth is used for inclination only: Android does not report barrel rotation.
pub fn stylus_input(ctx: &egui::Context, raw: &mut egui::RawInput) -> Option<StylusSample> {
    *CONTEXT.get_or_init(|| Mutex::new(None)).lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(ctx.clone());
    let installed = ctx.data_mut(|d| {
        let id = egui::Id::new("craft.android.platform");
        let installed = d.get_temp::<bool>(id).unwrap_or(false);
        d.insert_temp(id, true);
        installed
    });
    if !installed {
        ctx.on_end_pass(
            "Android clipboard and links",
            std::sync::Arc::new(|ui| {
                let commands = ui.ctx().output_mut(|o| std::mem::take(&mut o.commands));
                for command in commands {
                    let result = match command {
                        egui::OutputCommand::CopyText(text) => Clipboard.set_text(text),
                        egui::OutputCommand::CopyImage(image) => Clipboard.set_image(ImageData {
                            width: image.width(),
                            height: image.height(),
                            bytes: Cow::Owned(image.pixels.iter().flat_map(|p| p.to_srgba_unmultiplied()).collect()),
                        }),
                        egui::OutputCommand::OpenUrl(url) => that(url.url),
                    };
                    if let Err(e) = result {
                        report_error(&e);
                    }
                }
            }),
        );
    }
    if let Ok(state) = call("keyboardState", "")
        && let Ok(state) = serde_json::from_str::<KeyboardState>(&state)
    {
        let modifiers = keyboard_modifiers(state.modifiers);
        for event in &mut raw.events {
            match event {
                egui::Event::Key { modifiers: m, .. } | egui::Event::PointerButton { modifiers: m, .. } => *m = modifiers,
                _ => {}
            }
        }
        for (name, pressed, meta, repeat) in state.keys {
            if let Some(key) = egui::Key::from_name(&name) {
                let modifiers = keyboard_modifiers(meta);
                raw.events.push(egui::Event::ModifiersChanged(modifiers));
                raw.events.push(egui::Event::Key { key, physical_key: Some(key), pressed, repeat, modifiers });
                if pressed && modifiers.command {
                    match key {
                        egui::Key::C => raw.events.push(egui::Event::Copy),
                        egui::Key::X => raw.events.push(egui::Event::Cut),
                        egui::Key::V => {
                            if let Ok(text) = Clipboard.get_text() {
                                raw.events.push(egui::Event::Paste(text));
                            }
                        }
                        _ => {}
                    }
                }
            }
        }
        if !raw.events.is_empty() || ctx.input(|i| i.modifiers) != modifiers {
            raw.events.push(egui::Event::ModifiersChanged(modifiers));
        }
        if state.back
            && let Some(viewport) = raw.viewports.get_mut(&egui::ViewportId::ROOT)
        {
            viewport.events.push(egui::ViewportEvent::Close);
        }
    }
    let ready_widget = READY_WIDGET.with(|w| w.borrow_mut().take());
    if REPLY.with(|r| r.borrow().as_ref().is_some_and(|r| r.3.elapsed().as_secs() > 5)) {
        REPLY.with(|r| r.borrow_mut().take());
        report_error("The original file field is no longer available. Please select the file again.");
    }
    let scale = ctx.pixels_per_point().max(0.01);
    if let Ok(insets) = call("safeInsets", "")
        && let Ok([left, top, right, bottom]) = serde_json::from_str::<[f32; 4]>(&insets)
    {
        raw.safe_area_insets = Some(egui::SafeAreaInsets(egui::epaint::MarginF32 {
            left: left / scale,
            top: top / scale,
            right: right / scale,
            bottom: bottom / scale,
        }));
    }
    let events = call("drainPen", "").ok()?;
    let samples: Vec<[f64; 11]> = serde_json::from_str(&events).ok()?;
    let mut last = None;
    for sample in samples {
        if !sample.iter().all(|v| v.is_finite()) {
            continue;
        }
        let [action, x, y, pressure, tilt, orientation, tool, buttons, id, vertical_scroll, horizontal_scroll] = sample;
        let action = action as i32;
        let pos = egui::pos2(x as f32 / scale, y as f32 / scale);
        let [tilt_x, tilt_y] = pen_tilt(tilt as f32, orientation as f32);
        raw.events.push(egui::Event::PointerMoved(pos));
        let ended = matches!(action, 1 | 3 | 6 | 10);
        let mouse = tool as i32 == 3;
        last = Some(StylusSample { active: !ended && !mouse, pressure: (pressure as f32).clamp(0.0, 1.0), tilt_x, tilt_y, eraser: tool as i32 == 4 });
        let down = matches!(action, 0 | 2 | 5);
        let buttons = buttons as u32;
        let pressed = if ended {
            0
        } else if mouse {
            (buttons & 7) as u8
        } else if buttons & 64 != 0 {
            4
        } else if buttons & 32 != 0 {
            2
        } else if down {
            1
        } else {
            0
        };
        PEN_BUTTONS.with(|state| {
            let previous = state.replace(pressed);
            for (bit, button) in [(1, egui::PointerButton::Primary), (2, egui::PointerButton::Secondary), (4, egui::PointerButton::Middle)] {
                if (previous ^ pressed) & bit != 0 {
                    raw.events.push(egui::Event::PointerButton { pos, button, pressed: pressed & bit != 0, modifiers: ctx.input(|i| i.modifiers) });
                }
            }
        });
        let phase = match action {
            0 | 5 => Some(egui::TouchPhase::Start),
            2 => Some(egui::TouchPhase::Move),
            1 | 6 => Some(egui::TouchPhase::End),
            3 => Some(egui::TouchPhase::Cancel),
            _ => None,
        };
        if let Some(phase) = phase
            && !mouse
        {
            raw.events.push(egui::Event::Touch {
                device_id: egui::TouchDeviceId(u64::MAX),
                id: egui::TouchId(id.max(0.0) as u64),
                phase,
                pos,
                force: Some((pressure as f32).clamp(0.0, 1.0)),
            });
        }
        if mouse && action == 8 {
            raw.events.push(egui::Event::MouseWheel {
                unit: egui::MouseWheelUnit::Line,
                delta: egui::vec2(horizontal_scroll as f32, vertical_scroll as f32),
                phase: egui::TouchPhase::Move,
                modifiers: ctx.input(|i| i.modifiers),
            });
        }
        if matches!(action, 3 | 10) {
            raw.events.push(egui::Event::PointerGone);
        }
    }
    if dialog_pending() {
        // Match a desktop modal picker: document-changing input waits, but cancelled contacts
        // still release capture. A concurrent share intent is queued until this operation ends.
        raw.events.retain(|e| {
            matches!(
                e,
                egui::Event::PointerButton { pressed: false, .. }
                    | egui::Event::PointerGone
                    | egui::Event::Touch { phase: egui::TouchPhase::End | egui::TouchPhase::Cancel, .. }
                    | egui::Event::ModifiersChanged(_)
                    | egui::Event::WindowFocused(_)
            )
        });
    }
    if let Some(id) = ready_widget {
        raw.events.push(egui::Event::AccessKitActionRequest(egui::accesskit::ActionRequest {
            action: egui::accesskit::Action::Click,
            target_tree: egui::accesskit::TreeId::ROOT,
            target_node: id.accesskit_id(),
            data: None,
        }));
    }
    last
}

#[derive(Deserialize, Default)]
#[serde(default)]
struct KeyboardState {
    modifiers: u32,
    keys: Vec<(String, bool, u32, bool)>,
    back: bool,
}
fn keyboard_modifiers(meta: u32) -> egui::Modifiers {
    let ctrl = meta & 0x7000 != 0;
    let meta_key = meta & 0x70000 != 0;
    egui::Modifiers { alt: meta & 0x32 != 0, shift: meta & 0xc1 != 0, ctrl, mac_cmd: false, command: ctrl || meta_key }
}

fn with_activity<T>(f: impl FnOnce(&mut jni::JNIEnv<'_>, &JObject<'_>) -> jni::errors::Result<T>) -> Result<T, String> {
    let app = APP.get().ok_or("Android application is not initialized")?;
    // SAFETY: android-activity owns these VM/activity pointers for the whole activity lifetime.
    // The VM attachment and borrowed JNI reference remain inside this synchronous call.
    #[allow(unsafe_code)]
    let vm = unsafe { jni::JavaVM::from_raw(app.vm_as_ptr().cast()) }.map_err(|e| e.to_string())?;
    let mut env = vm.attach_current_thread().map_err(|e| e.to_string())?;
    #[allow(unsafe_code)]
    let activity = unsafe { JObject::from_raw(app.activity_as_ptr().cast()) };
    let result = env.with_local_frame(32, |env| f(env, &activity)).map_err(|e| e.to_string());
    if env.exception_check().unwrap_or(false) {
        let _ = env.exception_describe();
        let _ = env.exception_clear();
    }
    result
}

fn call(method: &str, text: &str) -> Result<String, String> {
    with_activity(|env, activity| {
        let argument = env.new_string(text)?;
        let result = env.call_method(activity, method, "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&argument)])?.l()?;
        if result.is_null() {
            return Ok(String::new());
        }
        Ok(env.get_string(&JString::from(result))?.into())
    })
}

pub fn report_error(message: &str) {
    log::error!("{message}");
    if let Err(error) = call("showError", message) {
        log::error!("Could not show error: {error}");
    }
}

pub struct CommandGuard(Option<(String, serde_json::Value)>);
pub fn command(id: &str, params: serde_json::Value) -> CommandGuard {
    CommandGuard(COMMAND.with(|c| c.replace(Some((id.to_string(), params)))))
}
impl Drop for CommandGuard {
    fn drop(&mut self) {
        COMMAND.with(|c| c.replace(self.0.take()));
    }
}

/// Retain a typed UI continuation only if this operation actually opens an Android picker.
pub struct CallbackGuard {
    id: u64,
    previous: Option<u64>,
}
pub fn resume_with<A: 'static>(callback: impl Fn(&mut A) + 'static) -> CallbackGuard {
    let id = NEXT_CALLBACK.with(|n| {
        let id = n.get().wrapping_add(1);
        n.set(id);
        id
    });
    let callback: Callback<A> = std::rc::Rc::new(callback);
    CALLBACKS.with(|c| {
        c.borrow_mut().insert(id, Box::new(callback));
    });
    let previous = CALLBACK.with(|c| {
        let previous = *c.borrow();
        if previous.is_none() {
            c.replace(Some(id));
        }
        previous
    });
    CallbackGuard { id, previous }
}
impl Drop for CallbackGuard {
    fn drop(&mut self) {
        CALLBACK.with(|c| c.replace(self.previous));
        if !PENDING.with(|p| p.borrow().as_ref().is_some_and(|p| p.callback == Some(self.id))) {
            CALLBACKS.with(|c| {
                c.borrow_mut().remove(&self.id);
            });
        }
    }
}
pub fn take_callback<A: 'static>() -> Option<Callback<A>> {
    let id = READY_CALLBACK.with(|c| c.borrow_mut().take())?;
    let callback = CALLBACKS.with(|c| c.borrow_mut().remove(&id))?;
    match callback.downcast::<Callback<A>>() {
        Ok(callback) => Some(*callback),
        Err(_) => {
            report_error("The file operation belongs to a different application instance");
            None
        }
    }
}

pub fn dialog_pending() -> bool {
    PENDING.with(|p| p.borrow().is_some()) || REPLY.with(|r| r.borrow().is_some()) || READY_CALLBACK.with(|c| c.borrow().is_some())
}

pub fn choose(dialog: FileDialog, kind: &str) -> Option<Vec<PathBuf>> {
    if let Some((expected, expected_kind, paths, _)) = REPLY.with(|r| r.borrow_mut().take()) {
        if expected == dialog && expected_kind == kind {
            return Some(paths);
        }
        report_error("The file operation changed while the picker was open. Please try again.");
        return None;
    }
    if PENDING.with(|p| p.borrow().is_some()) {
        return None;
    }
    let command = COMMAND.with(|c| c.borrow().clone());
    let callback = CALLBACK.with(|c| *c.borrow());
    let widget = CONTEXT.get().and_then(|c| c.lock().ok()).and_then(|c| c.as_ref().and_then(|ctx| ctx.interaction_snapshot(|s| s.clicked)));
    let mut random = [0u8; 16];
    if let Err(error) = getrandom::fill(&mut random) {
        report_error(&error.to_string());
        return None;
    }
    let id: String = random.iter().map(|b| format!("{b:02x}")).collect();
    let request = serde_json::json!({"id": id, "kind": kind, "dialog": dialog});
    match call("beginFileDialog", &request.to_string()) {
        Ok(_) => PENDING.with(|p| {
            p.replace(Some(Pending { id, command, callback, widget, dialog, kind: kind.into() }));
        }),
        Err(e) => report_error(&format!("Could not open file picker: {e}")),
    }
    None
}

/// Complete dialogs on the rendering thread. The Android main/event thread is never blocked.
pub fn completed_command(ctx: &egui::Context) -> Option<(String, serde_json::Value)> {
    *CONTEXT.get_or_init(|| Mutex::new(None)).lock().unwrap_or_else(std::sync::PoisonError::into_inner) = Some(ctx.clone());
    if !dialog_pending() {
        let incoming = INCOMING.with(|q| std::mem::take(&mut *q.borrow_mut()));
        ctx.input_mut(|i| {
            i.raw
                .dropped_files
                .extend(incoming.into_iter().map(|p| std::sync::Arc::new(AndroidFile(p)) as std::sync::Arc<dyn egui::DroppedFile + Send + Sync>))
        });
    }
    if PENDING.with(|p| p.borrow().is_some()) {
        ctx.request_repaint_after(std::time::Duration::from_millis(50));
    }
    let response = match call("pollFileDialog", "") {
        Ok(response) if !response.is_empty() => response,
        Ok(_) => return None,
        Err(e) => {
            report_error(&e);
            return None;
        }
    };
    let response: serde_json::Value = match serde_json::from_str(&response) {
        Ok(v) => v,
        Err(e) => {
            report_error(&e.to_string());
            return None;
        }
    };
    // A share/Open With intent can arrive while a save picker is open. It must never become
    // that save's destination, nor consume the pending save continuation.
    let incoming = response.get("source").and_then(serde_json::Value::as_str) == Some("intent");
    let pending = if incoming {
        None
    } else {
        let matches =
            PENDING.with(|p| p.borrow().as_ref().is_some_and(|p| response.get("id").and_then(serde_json::Value::as_str) == Some(p.id.as_str())));
        if !matches {
            log::warn!("Ignoring a stale Android file-picker result");
            return None;
        }
        PENDING.with(|p| p.borrow_mut().take())
    };
    if let Some(e) = response.get("error").and_then(serde_json::Value::as_str) {
        report_error(e);
    }
    let paths: Vec<PathBuf> = response
        .get("paths")
        .and_then(serde_json::Value::as_array)
        .into_iter()
        .flatten()
        .filter_map(serde_json::Value::as_str)
        .map(PathBuf::from)
        .collect();
    if paths.is_empty() {
        if let Some(id) = pending.and_then(|p| p.callback) {
            CALLBACKS.with(|c| {
                c.borrow_mut().remove(&id);
            });
        }
        return None;
    }
    if let Some(id) = pending.as_ref().and_then(|p| p.callback) {
        if let Some(p) = pending {
            REPLY.with(|r| r.replace(Some((p.dialog, p.kind, paths, std::time::Instant::now()))));
        }
        READY_CALLBACK.with(|c| c.replace(Some(id)));
        None
    } else if let Some(p) = pending {
        REPLY.with(|r| r.replace(Some((p.dialog, p.kind, paths, std::time::Instant::now()))));
        if let Some(command) = p.command {
            Some(command)
        } else if let Some(widget) = p.widget {
            READY_WIDGET.with(|w| w.replace(Some(widget)));
            ctx.request_repaint();
            ctx.request_repaint_after(std::time::Duration::from_secs(6));
            None
        } else {
            REPLY.with(|r| r.borrow_mut().take());
            report_error("The file picker has no operation to resume. Please use File > Open.");
            None
        }
    } else {
        INCOMING.with(|q| q.borrow_mut().extend(paths));
        ctx.request_repaint();
        None
    }
}

#[derive(Debug)]
struct AndroidFile(PathBuf);
impl egui::DroppedFile for AndroidFile {
    fn path(&self) -> &Path {
        &self.0
    }
    fn bytes(&self) -> Result<Vec<u8>, String> {
        std::fs::read(&self.0).map_err(|e| e.to_string())
    }
}

pub fn that(path: impl AsRef<std::ffi::OsStr>) -> Result<(), String> {
    let file = Path::new(path.as_ref());
    let target = if file.exists() {
        std::path::absolute(file).map_err(|e| e.to_string())?.to_string_lossy().into_owned()
    } else {
        path.as_ref().to_string_lossy().into_owned()
    };
    call("openExternal", &target).map(|_| ())
}

pub fn open_with(path: &str, package: &str) -> Result<(), String> {
    let path = std::path::absolute(path).map_err(|e| e.to_string())?;
    call("openExternal", &serde_json::json!({"location": path, "package": package, "edit": true}).to_string()).map(|_| ())
}

pub fn reveal(path: &str) -> Result<(), String> {
    call("revealExternal", &std::path::absolute(path).map_err(|e| e.to_string())?.to_string_lossy()).map(|_| ())
}

pub fn move_document(from: &Path, to: &Path) -> Result<(), String> {
    let from = std::path::absolute(from).map_err(|e| e.to_string())?;
    let to = std::path::absolute(to).map_err(|e| e.to_string())?;
    let error = call("moveDocument", &serde_json::json!({"from": from, "to": to}).to_string())?;
    if error.is_empty() { Ok(()) } else { Err(error) }
}

pub fn remove_document(path: &Path) -> Result<(), String> {
    let error = call("removeDocument", &std::path::absolute(path).map_err(|e| e.to_string())?.to_string_lossy())?;
    if error.is_empty() { Ok(()) } else { Err(error) }
}

pub fn publish_file(path: &Path) -> Result<(), String> {
    let path = std::path::absolute(path).map_err(|e| e.to_string())?;
    call("publishFile", &path.to_string_lossy()).and_then(|error| if error.is_empty() { Ok(()) } else { Err(error) })
}

pub fn print_pdf(bytes: &[u8], title: &str, grayscale: bool, duplex: u8) -> Result<String, String> {
    let dir = APP.get().and_then(|a| a.internal_data_path()).ok_or("No application data directory")?.join("PrintJobs");
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;
    let stamp = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_err(|e| e.to_string())?.as_nanos();
    let path = dir.join(format!("{stamp}.pdf"));
    std::fs::write(&path, bytes).map_err(|e| e.to_string())?;
    call("printPdf", &serde_json::json!({"path": path, "title": title, "grayscale": grayscale, "duplex": duplex}).to_string())?;
    Ok("Opened Android print dialog".into())
}

pub fn require_microphone_permission() -> Result<(), String> {
    let response = call("microphonePermission", "")?;
    if response == "granted" { Ok(()) } else { Err(response) }
}

pub struct Clipboard;
impl Clipboard {
    pub fn new() -> Result<Self, String> {
        Ok(Self)
    }
    pub fn get_text(&mut self) -> Result<String, String> {
        call("getClipboardText", "")
    }
    pub fn set_text(&mut self, text: impl Into<String>) -> Result<(), String> {
        call("setClipboardText", &text.into()).map(|_| ())
    }
    pub fn clear(&mut self) -> Result<(), String> {
        self.set_text("")
    }
    pub fn set_image(&mut self, image: ImageData<'_>) -> Result<(), String> {
        let w = u32::try_from(image.width).map_err(|e| e.to_string())?;
        let h = u32::try_from(image.height).map_err(|e| e.to_string())?;
        let buffer = image::RgbaImage::from_raw(w, h, image.bytes.into_owned()).ok_or("Invalid clipboard image size")?;
        let folder = APP.get().and_then(|a| a.internal_data_path()).ok_or("No app directory")?.join("Clipboard");
        std::fs::create_dir_all(&folder).map_err(|e| e.to_string())?;
        let path = folder.join("image.png");
        buffer.save(&path).map_err(|e| e.to_string())?;
        call("setClipboardImage", &path.to_string_lossy()).map(|_| ())
    }
    pub fn get_image(&mut self) -> Result<ImageData<'static>, String> {
        let path = call("getClipboardImage", "")?;
        let image = image::ImageReader::open(path)
            .map_err(|e| e.to_string())?
            .with_guessed_format()
            .map_err(|e| e.to_string())?
            .decode()
            .map_err(|e| e.to_string())?
            .into_rgba8();
        Ok(ImageData { width: image.width() as usize, height: image.height() as usize, bytes: Cow::Owned(image.into_raw()) })
    }
}

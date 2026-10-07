//! Android platform boundary. Editing and rendering remain in the original Rust application.
use serde::{Deserialize, Serialize};
use std::borrow::Cow;
use std::path::{Path, PathBuf};

#[cfg(target_os = "android")]
mod native;
#[cfg(target_os = "android")]
pub use native::*;

#[derive(Clone, Debug, Default, Serialize, Deserialize, PartialEq)]
pub struct FileDialog {
    pub title: String,
    pub name: String,
    pub directory: Option<PathBuf>,
    pub extensions: Vec<String>,
}

impl FileDialog {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }
    pub fn set_file_name(mut self, name: impl Into<String>) -> Self {
        self.name = name.into();
        self
    }
    pub fn set_directory(mut self, directory: impl AsRef<Path>) -> Self {
        self.directory = Some(directory.as_ref().to_path_buf());
        self
    }
    pub fn add_filter(mut self, _name: impl Into<String>, extensions: &[impl ToString]) -> Self {
        self.extensions.extend(extensions.iter().map(ToString::to_string));
        self
    }
    pub fn pick_file(self) -> Option<PathBuf> {
        self.choose("open").and_then(|v| v.into_iter().next())
    }
    pub fn pick_files(self) -> Option<Vec<PathBuf>> {
        self.choose("open_multiple")
    }
    pub fn pick_folder(self) -> Option<PathBuf> {
        self.choose("folder").and_then(|v| v.into_iter().next())
    }
    pub fn pick_folders(self) -> Option<Vec<PathBuf>> {
        self.choose("folder")
    }
    pub fn save_file(self) -> Option<PathBuf> {
        self.choose("save").and_then(|v| v.into_iter().next())
    }
    fn choose(self, kind: &str) -> Option<Vec<PathBuf>> {
        #[cfg(target_os = "android")]
        {
            native::choose(self, kind)
        }
        #[cfg(not(target_os = "android"))]
        {
            let _ = (self, kind);
            None
        }
    }
}

#[derive(Clone, Debug, Default)]
pub enum MessageLevel {
    #[default]
    Info,
    Warning,
    Error,
}
#[derive(Clone, Debug, PartialEq)]
pub enum MessageDialogResult {
    Ok,
}
#[derive(Default)]
pub struct MessageDialog {
    title: String,
    text: String,
}
impl MessageDialog {
    pub fn new() -> Self {
        Self::default()
    }
    pub fn set_title(mut self, title: impl Into<String>) -> Self {
        self.title = title.into();
        self
    }
    pub fn set_description(mut self, text: impl Into<String>) -> Self {
        self.text = text.into();
        self
    }
    pub fn set_level(self, _level: MessageLevel) -> Self {
        self
    }
    pub fn show(self) -> MessageDialogResult {
        #[cfg(target_os = "android")]
        report_error(&format!("{}: {}", self.title, self.text));
        MessageDialogResult::Ok
    }
}

pub struct ImageData<'a> {
    pub width: usize,
    pub height: usize,
    pub bytes: Cow<'a, [u8]>,
}

#[derive(Clone, Copy, Debug, Default)]
pub struct StylusSample {
    pub active: bool,
    pub pressure: f32,
    pub tilt_x: f32,
    pub tilt_y: f32,
    pub eraser: bool,
}

/// Android reports inclination from the normal and clockwise azimuth from screen-up.
/// W3C tiltX/tiltY are signed angles from the normal towards screen-right/screen-down.
pub fn pen_tilt(tilt: f32, orientation: f32) -> [f32; 2] {
    if !tilt.is_finite() || !orientation.is_finite() {
        return [0.0; 2];
    }
    let tilt = tilt.clamp(0.0, std::f32::consts::FRAC_PI_2);
    let z = tilt.cos().max(0.0);
    [(tilt.sin() * orientation.sin()).atan2(z).to_degrees(), (-tilt.sin() * orientation.cos()).atan2(z).to_degrees()]
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn stylus_axes_follow_android_coordinates() {
        assert_eq!(pen_tilt(0.0, 1.0), [0.0, -0.0]);
        let upward = pen_tilt(std::f32::consts::FRAC_PI_4, 0.0);
        assert!(upward[0].abs() < 0.001 && (upward[1] + 45.0).abs() < 0.001);
        let right = pen_tilt(std::f32::consts::FRAC_PI_4, std::f32::consts::FRAC_PI_2);
        assert!((right[0] - 45.0).abs() < 0.001 && right[1].abs() < 0.001);
        assert_eq!(pen_tilt(f32::NAN, 0.0), [0.0; 2]);
    }
}

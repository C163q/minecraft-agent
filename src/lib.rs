//! Parser and per-tick keyboard state for Minecraft input scripts.
//!
//! ```
//! use minecraft_input::{Keyboard, Script};
//! let script = Script::parse("BEGIN\nKeyPress(w)\nWait(5)\nKeyRelease(w)\nEND")?;
//! let mut keyboard = Keyboard::default();
//! let held_ticks = keyboard.play(&script).filter(|tick| tick.held.contains("w")).count();
//! assert_eq!(held_ticks, 6);
//! # Ok::<(), minecraft_input::ParseError>(())
//! ```

mod keyboard;
mod parser;

pub use keyboard::{DispatchProgram, KeyFrame, KeyRun, Keyboard};
pub use parser::{ParseError, Script, ScriptSource};

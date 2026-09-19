# Pool Trajectory Lab — clean-room prototype

This is a fresh implementation inspired only by observed behavior/architecture. It does **not** include or reuse Aim Pool's proprietary `libfolysurrhy.so`, decompiled source, assets, or models.

## What is implemented

- Android `MediaProjection` capture through a foreground service.
- `TYPE_APPLICATION_OVERLAY` transparent trajectory view.
- Fail-closed scene validation: no overlay unless a large, stable, felt-like pool-table ROI is found.
- Cue-ball detector based on a solid white circular region rather than generic circle detection.
- Aim-guide detector using radial scoring from the cue ball.
- Object-ball candidates using table-color difference + connected components.
- Geometric ray/ball collision and two post-impact lines.
- Multi-frame stabilizer to reduce jitter and clear hallucinated results after invalid frames.
- No external computer-vision dependency.

## Why this should behave better than the earlier prototype

The earlier approach could search the whole screen for circles and therefore interpreted unrelated UI as balls. This version first validates the table ROI, then searches only inside it, and refuses to draw when confidence is low.

## Build

Open the project in Android Studio. It targets SDK 35 and has no AndroidX/runtime dependencies.

1. Sync Gradle.
2. Build/install the `app` module.
3. Open the app and grant overlay permission.
4. Press **Start detector** and approve Android screen capture.
5. Open the pool game.

## First testing pass

Turn the pool game into a normal aiming scene and take screenshots if one of these happens:

- table ROI box misses part of the table;
- cue ball circle appears on the wrong object;
- main line points in the opposite direction;
- collision ball is wrong;
- no line appears even though the game's own aiming guide is clearly visible.

Those cases can be tuned in `VisionEngine.java` without touching capture/overlay code.

## Important limitation

The original Aim Pool APK base package references a native library named `libfolysurrhy.so`; that library was not present in the supplied base APK. Therefore this project recreates the pipeline with a new detector instead of reproducing the unknown native recognition algorithm.

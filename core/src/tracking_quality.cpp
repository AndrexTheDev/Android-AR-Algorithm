#include "arkernel/tracking_quality.h"
#include <stdexcept>

namespace arkernel {

void TrackingQualityGate::reset() {
  state_ = TrackingState::kUninitialized;
  good_frames_ = bad_frames_ = 0;
}

TrackingState TrackingQualityGate::update(int inliers, double error) {
  const bool good = inliers >= policy_.min_inliers_tracking &&
                    error <= policy_.max_error_tracking_px;
  const bool usable = inliers >= policy_.min_inliers_limited &&
                      error <= policy_.max_error_limited_px;
  good_frames_ = good ? good_frames_ + 1 : 0;
  bad_frames_ = usable ? 0 : bad_frames_ + 1;

  if (good_frames_ >= policy_.good_frames_before_recovered) state_ = TrackingState::kTracking;
  else if (bad_frames_ >= policy_.bad_frames_before_lost) state_ = TrackingState::kLost;
  else if (!good && state_ != TrackingState::kLost) state_ = TrackingState::kLimited;
  return state_;
}

MappingQualityGate::MappingQualityGate(MappingGatePolicy policy):policy_(policy){
  if(policy_.min_accepted_tracks<1||policy_.good_frames_before_resume<1||
     policy_.bad_frames_before_pause<1)throw std::invalid_argument("Invalid mapping gate policy");
}
void MappingQualityGate::reset(){
  state_=MappingGateState::kPaused;good_frames_=bad_frames_=0;
}
MappingGateDecision MappingQualityGate::update(bool pose_initialized,bool pose_limited,
    bool visual_tracking,std::size_t accepted_tracks,bool imu_queue_overflow){
  MappingPauseReason failure=MappingPauseReason::kNone;
  if(!pose_initialized)failure=MappingPauseReason::kPoseUninitialized;
  else if(pose_limited)failure=MappingPauseReason::kPoseLimited;
  else if(imu_queue_overflow)failure=MappingPauseReason::kImuOverflow;
  else if(!visual_tracking)failure=MappingPauseReason::kVisualNotTracking;
  else if(accepted_tracks<policy_.min_accepted_tracks)failure=MappingPauseReason::kTooFewTracks;
  if(failure!=MappingPauseReason::kNone){
    good_frames_=0;++bad_frames_;
    if(bad_frames_>=policy_.bad_frames_before_pause)state_=MappingGateState::kPaused;
    return {state_,state_==MappingGateState::kPaused?failure:MappingPauseReason::kNone,
            state_==MappingGateState::kActive};
  }
  bad_frames_=0;
  if(state_==MappingGateState::kActive)return {state_,MappingPauseReason::kNone,true};
  ++good_frames_;
  if(good_frames_>=policy_.good_frames_before_resume){
    state_=MappingGateState::kActive;return {state_,MappingPauseReason::kNone,true};
  }
  state_=MappingGateState::kRecovering;
  return {state_,MappingPauseReason::kRecovering,false};
}

}  // namespace arkernel

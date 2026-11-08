#include "arkernel/place_recognition.h"

#include <algorithm>
#include <cmath>
#include <limits>
#include <stdexcept>

namespace arkernel {
namespace {
constexpr std::size_t kWordsPerDescriptor=4;
std::uint32_t descriptorWord(const BinaryFeatureDescriptor& descriptor,std::size_t chunk,
                             std::size_t vocabulary_words) {
  std::uint32_t hash=2166136261u^static_cast<std::uint32_t>(chunk*0x9e3779b9U);
  const std::size_t begin=chunk*(descriptor.bytes.size()/kWordsPerDescriptor);
  const std::size_t end=(chunk+1)*(descriptor.bytes.size()/kWordsPerDescriptor);
  for(std::size_t i=begin;i<end;++i){hash^=descriptor.bytes[i];hash*=16777619u;}
  return static_cast<std::uint32_t>(hash%vocabulary_words);
}
TimestampNs absoluteDifference(TimestampNs a,TimestampNs b) {
  if(a>=b)return a-b;
  return b-a;
}
}  // namespace

PlaceRecognitionDatabase::PlaceRecognitionDatabase(PlaceRecognitionConfig config)
    :config_(config),document_frequency_(config.vocabulary_words,0) {
  if(config_.max_keyframes==0||config_.max_keyframes>100000||
     config_.max_descriptors_per_keyframe==0||config_.max_descriptors_per_keyframe>1000000||
     config_.vocabulary_words<64||config_.vocabulary_words>1048576||
     config_.max_candidates==0||config_.max_candidates>config_.max_keyframes||
     config_.min_shared_words==0||config_.min_shared_words>config_.vocabulary_words||
     config_.min_temporal_separation_ns<0||!std::isfinite(config_.min_similarity)||
     config_.min_similarity<=0||config_.min_similarity>1)
    throw std::invalid_argument("Invalid place recognition configuration");
}

std::vector<PlaceRecognitionDatabase::WordCount> PlaceRecognitionDatabase::makeWords(
    const std::vector<BinaryFeatureDescriptor>& descriptors) const {
  if(descriptors.empty()||descriptors.size()>config_.max_descriptors_per_keyframe)return {};
  std::vector<std::uint32_t> words;words.reserve(descriptors.size()*kWordsPerDescriptor);
  for(const auto& descriptor:descriptors)for(std::size_t chunk=0;chunk<kWordsPerDescriptor;++chunk)
    words.push_back(descriptorWord(descriptor,chunk,config_.vocabulary_words));
  std::sort(words.begin(),words.end());
  std::vector<WordCount> output;output.reserve(words.size());
  for(const auto word:words){
    if(output.empty()||output.back().word!=word)output.push_back({word,1});
    else if(output.back().count<std::numeric_limits<std::uint32_t>::max())++output.back().count;
  }
  return output;
}

void PlaceRecognitionDatabase::evictOne() {
  if(entries_.empty())return;
  auto victim=entries_.begin();
  for(auto iterator=entries_.begin();iterator!=entries_.end();++iterator)
    if(iterator->second.timestamp_ns<victim->second.timestamp_ns||
       (iterator->second.timestamp_ns==victim->second.timestamp_ns&&iterator->first<victim->first))
      victim=iterator;
  for(const auto& word:victim->second.words)--document_frequency_[word.word];
  entries_.erase(victim);
}

bool PlaceRecognitionDatabase::insert(RecognitionKeyframeId id,TimestampNs timestamp,
    const std::vector<BinaryFeatureDescriptor>& descriptors) {
  if(id==0||timestamp<=0||entries_.count(id)!=0)return false;
  auto words=makeWords(descriptors);if(words.empty())return false;
  if(entries_.size()>=config_.max_keyframes)evictOne();
  for(const auto& word:words)++document_frequency_[word.word];
  entries_.emplace(id,Entry{id,timestamp,std::move(words)});return true;
}

std::vector<PlaceRecognitionCandidate> PlaceRecognitionDatabase::query(
    TimestampNs timestamp,const std::vector<BinaryFeatureDescriptor>& descriptors) const {
  std::vector<PlaceRecognitionCandidate> output;
  if(timestamp<=0||entries_.empty())return output;
  const auto query_words=makeWords(descriptors);if(query_words.empty())return output;
  const double document_count=static_cast<double>(entries_.size());
  auto weight=[&](const WordCount& word){
    const double idf=std::log((document_count+1.0)/
      (static_cast<double>(document_frequency_[word.word])+1.0))+1.0;
    return (1.0+std::log(static_cast<double>(word.count)))*idf;
  };
  double query_norm_squared=0;
  for(const auto& word:query_words){const double value=weight(word);query_norm_squared+=value*value;}
  if(query_norm_squared<=0)return output;
  for(const auto& item:entries_){const Entry& entry=item.second;
    if(absoluteDifference(timestamp,entry.timestamp_ns)<config_.min_temporal_separation_ns)continue;
    std::size_t query_index=0,entry_index=0,shared=0;double dot=0,entry_norm_squared=0;
    for(const auto& word:entry.words){const double value=weight(word);entry_norm_squared+=value*value;}
    while(query_index<query_words.size()&&entry_index<entry.words.size()){
      const auto query_word=query_words[query_index].word;
      const auto entry_word=entry.words[entry_index].word;
      if(query_word<entry_word){++query_index;continue;}
      if(entry_word<query_word){++entry_index;continue;}
      dot+=weight(query_words[query_index])*weight(entry.words[entry_index]);
      ++shared;++query_index;++entry_index;
    }
    if(shared<config_.min_shared_words||entry_norm_squared<=0)continue;
    const double similarity=dot/std::sqrt(query_norm_squared*entry_norm_squared);
    if(!std::isfinite(similarity)||similarity<config_.min_similarity)continue;
    output.push_back({entry.id,entry.timestamp_ns,std::min(1.0,similarity),shared});
  }
  std::sort(output.begin(),output.end(),[](const auto& a,const auto& b){
    if(a.similarity!=b.similarity)return a.similarity>b.similarity;
    if(a.shared_words!=b.shared_words)return a.shared_words>b.shared_words;
    if(a.timestamp_ns!=b.timestamp_ns)return a.timestamp_ns>b.timestamp_ns;
    return a.keyframe_id<b.keyframe_id;
  });
  if(output.size()>config_.max_candidates)output.resize(config_.max_candidates);
  return output;
}

void PlaceRecognitionDatabase::reset(){entries_.clear();
  std::fill(document_frequency_.begin(),document_frequency_.end(),0);}

}  // namespace arkernel

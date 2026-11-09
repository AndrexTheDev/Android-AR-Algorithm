#include "arkernel/reconstruction_asset.h"

#include <algorithm>
#include <cctype>
#include <cmath>
#include <cstring>
#include <iomanip>
#include <limits>
#include <set>
#include <sstream>
#include <stdexcept>
#include <unordered_set>

namespace arkernel {
namespace {
bool finite(float value) { return std::isfinite(value); }
bool finite(double value) { return std::isfinite(value); }
bool validEvidenceId(const std::string& value) {
  if (value.empty() || value.size() > 128) return false;
  return std::all_of(value.begin(), value.end(), [](unsigned char c) {
    return std::isalnum(c) != 0 || c == '.' || c == '_' || c == ':' || c == '-';
  });
}
std::string escapeJson(const std::string& text) {
  std::ostringstream out;
  for (const unsigned char c : text) {
    switch (c) {
      case '"': out << "\\\""; break;
      case '\\': out << "\\\\"; break;
      case '\n': out << "\\n"; break;
      case '\r': out << "\\r"; break;
      case '\t': out << "\\t"; break;
      default:
        if (c < 0x20) out << "\\u" << std::hex << std::setw(4)
                          << std::setfill('0') << static_cast<int>(c) << std::dec;
        else out << static_cast<char>(c);
    }
  }
  return out.str();
}
template <typename T>
std::size_t append(std::vector<std::uint8_t>& output, const std::vector<T>& values) {
  while ((output.size() & 3U) != 0U) output.push_back(0);
  const std::size_t offset = output.size();
  if (values.size() > (std::numeric_limits<std::size_t>::max() - output.size()) / sizeof(T))
    throw std::length_error("Mesh binary exceeds addressable memory");
  const auto* bytes = reinterpret_cast<const std::uint8_t*>(values.data());
  output.insert(output.end(), bytes, bytes + values.size() * sizeof(T));
  return offset;
}
void appendU32Le(std::vector<std::uint8_t>& out, std::uint32_t value) {
  out.push_back(static_cast<std::uint8_t>(value));
  out.push_back(static_cast<std::uint8_t>(value>>8U));
  out.push_back(static_cast<std::uint8_t>(value>>16U));
  out.push_back(static_cast<std::uint8_t>(value>>24U));
}
bool littleEndianHost() {
  const std::uint16_t value=1;
  return *reinterpret_cast<const std::uint8_t*>(&value)==1;
}
const char* gradeName(MetricAccuracyGrade grade) {
  switch (grade) {
    case MetricAccuracyGrade::kCentimeterValidated: return "centimeter-validated";
    case MetricAccuracyGrade::kApproximate: return "approximate";
    default: return "unverified";
  }
}
}  // namespace

DimensionalValidationReport evaluateHeldOutDimensions(
    const std::vector<HeldOutDimensionObservation>& observations,
    const DimensionalValidationPolicy& p) {
  DimensionalValidationReport result;
  const bool policy_valid = p.max_observations > 0 &&
      p.min_observations > 0 && p.min_observations <= p.max_observations &&
      p.min_dimension_bins > 0 && p.min_dimension_bins <= p.min_observations &&
      finite(p.dimension_bin_width_m) && p.dimension_bin_width_m > 0.0 &&
      finite(p.max_reference_uncertainty_m) && p.max_reference_uncertainty_m >= 0.0 &&
      finite(p.max_mean_absolute_error_m) && p.max_mean_absolute_error_m > 0.0 &&
      finite(p.max_absolute_error_m) && p.max_absolute_error_m > 0.0 &&
      finite(p.max_relative_error) && p.max_relative_error > 0.0 &&
      p.max_relative_error <= 1.0;
  if (!policy_valid) {
    result.failures.push_back("invalid dimensional validation policy");
    return result;
  }
  if (observations.size() > p.max_observations)
    result.failures.push_back("dimensional observation budget exceeded");

  std::unordered_set<std::string> evidence_ids;
  std::set<std::int64_t> dimension_bins;
  double absolute_error_sum = 0.0;
  const std::size_t count = std::min(observations.size(), p.max_observations);
  for (std::size_t i = 0; i < count; ++i) {
    const auto& observation = observations[i];
    const Vec3 delta = observation.reconstructed_b_m - observation.reconstructed_a_m;
    const double reconstructed_distance = delta.norm();
    const bool id_valid = validEvidenceId(observation.evidence_id);
    const bool unique_id = id_valid && evidence_ids.insert(observation.evidence_id).second;
    const bool finite_points = finite(observation.reconstructed_a_m.x) &&
        finite(observation.reconstructed_a_m.y) &&
        finite(observation.reconstructed_a_m.z) &&
        finite(observation.reconstructed_b_m.x) &&
        finite(observation.reconstructed_b_m.y) &&
        finite(observation.reconstructed_b_m.z);
    const bool valid = id_valid && unique_id && finite_points &&
        finite(reconstructed_distance) && reconstructed_distance > 0.0 &&
        finite(observation.reference_distance_m) && observation.reference_distance_m > 0.0 &&
        finite(observation.reference_uncertainty_m) &&
        observation.reference_uncertainty_m >= 0.0 &&
        observation.reference_uncertainty_m <= p.max_reference_uncertainty_m &&
        observation.reference_uncertainty_m < observation.reference_distance_m &&
        observation.reference_kind != DimensionalReferenceKind::kUnknown &&
        observation.held_out_from_calibration_and_optimization;
    if (!valid) {
      if (std::find(result.failures.begin(), result.failures.end(),
                    "invalid or non-independent dimensional observation") ==
          result.failures.end())
        result.failures.push_back("invalid or non-independent dimensional observation");
      continue;
    }
    const double absolute_error = std::abs(reconstructed_distance -
                                           observation.reference_distance_m);
    const double effective_error = std::max(0.0, absolute_error -
                                                  observation.reference_uncertainty_m);
    absolute_error_sum += absolute_error;
    result.maximum_effective_absolute_error_m = std::max(
        result.maximum_effective_absolute_error_m, effective_error);
    result.maximum_effective_relative_error = std::max(
        result.maximum_effective_relative_error,
        effective_error / observation.reference_distance_m);
    result.maximum_validated_dimension_m = std::max(
        result.maximum_validated_dimension_m, observation.reference_distance_m);
    dimension_bins.insert(static_cast<std::int64_t>(std::floor(
        observation.reference_distance_m / p.dimension_bin_width_m)));
    ++result.accepted_observations;
  }
  result.dimension_bins = dimension_bins.size();
  if (result.accepted_observations > 0)
    result.mean_absolute_error_m = absolute_error_sum / result.accepted_observations;
  if (result.accepted_observations < p.min_observations)
    result.failures.push_back("insufficient held-out dimensional observations");
  if (result.dimension_bins < p.min_dimension_bins)
    result.failures.push_back("insufficient held-out dimension diversity");
  if (result.mean_absolute_error_m > p.max_mean_absolute_error_m)
    result.failures.push_back("mean dimensional error exceeds policy");
  if (result.maximum_effective_absolute_error_m > p.max_absolute_error_m)
    result.failures.push_back("maximum dimensional error exceeds policy");
  if (result.maximum_effective_relative_error > p.max_relative_error)
    result.failures.push_back("relative dimensional error exceeds policy");
  result.passed = result.failures.empty();
  return result;
}

AccuracyAssessment assessReconstructionQuality(const ReconstructionQuality& q,
                                                ScanMode mode,
                                                const AccuracyPolicy& p) {
  AccuracyAssessment result;
  const bool policy_valid = finite(p.max_reprojection_rmse_px) &&
      finite(p.max_depth_rmse_m) && finite(p.max_scale_stddev_ratio) &&
      finite(p.max_absolute_scale_error_m) && finite(p.min_environment_coverage_ratio) &&
      finite(p.min_object_coverage_ratio) && p.max_reprojection_rmse_px > 0.0 &&
      p.max_depth_rmse_m > 0.0 && p.max_scale_stddev_ratio > 0.0 &&
      p.max_absolute_scale_error_m > 0.0 && p.min_environment_coverage_ratio >= 0.0 &&
      p.min_environment_coverage_ratio <= 1.0 && p.min_object_coverage_ratio >= 0.0 &&
      p.min_object_coverage_ratio <= 1.0;
  if (!policy_valid) { result.failures.push_back("invalid accuracy policy"); return result; }
  result.dimensional_validation = evaluateHeldOutDimensions(
      q.held_out_dimensions, p.dimensional_validation);
  if (!result.dimensional_validation.failures.empty() &&
      result.dimensional_validation.failures.front() ==
          "invalid dimensional validation policy") {
    result.failures.push_back("invalid accuracy policy");
    return result;
  }
  if (!q.calibrated_intrinsics) result.failures.push_back("camera intrinsics are not calibrated");
  if (!q.calibrated_camera_imu) result.failures.push_back("camera-to-IMU calibration is missing");
  if (!q.metric_scale_observed) result.failures.push_back("metric scale has no independent observation");
  if (!q.globally_optimized) result.failures.push_back("global pose/loop optimization has not converged");
  if (!finite(q.reprojection_rmse_px) || q.reprojection_rmse_px < 0.0 ||
      q.reprojection_rmse_px > p.max_reprojection_rmse_px)
    result.failures.push_back("reprojection RMSE exceeds policy");
  if (!finite(q.depth_rmse_m) || q.depth_rmse_m < 0.0 || q.depth_rmse_m > p.max_depth_rmse_m)
    result.failures.push_back("metric depth RMSE exceeds one centimeter policy");
  if (!finite(q.scale_stddev_ratio) || q.scale_stddev_ratio < 0.0 ||
      q.scale_stddev_ratio > p.max_scale_stddev_ratio)
    result.failures.push_back("scale uncertainty exceeds policy");
  if (!finite(q.assessed_extent_m) || q.assessed_extent_m <= 0.0)
    result.failures.push_back("validated asset extent is missing");
  else if (finite(q.scale_stddev_ratio) && q.scale_stddev_ratio >= 0.0 &&
           q.assessed_extent_m * q.scale_stddev_ratio > p.max_absolute_scale_error_m)
    result.failures.push_back("absolute scale error exceeds one centimeter policy");
  if (!result.dimensional_validation.passed)
    result.failures.push_back("held-out dimensional validation failed");
  else if (finite(q.assessed_extent_m) && q.assessed_extent_m > 0.0 &&
           result.dimensional_validation.maximum_validated_dimension_m < q.assessed_extent_m)
    result.failures.push_back("held-out dimensions do not cover assessed extent");
  const double required_coverage = mode == ScanMode::kObject
      ? p.min_object_coverage_ratio : p.min_environment_coverage_ratio;
  if (!finite(q.surface_coverage_ratio) || q.surface_coverage_ratio < required_coverage ||
      q.surface_coverage_ratio > 1.0)
    result.failures.push_back("surface coverage is insufficient");

  result.export_allowed = q.calibrated_intrinsics && q.metric_scale_observed &&
      finite(q.surface_coverage_ratio) && q.surface_coverage_ratio > 0.0;
  if (result.failures.empty()) result.grade = MetricAccuracyGrade::kCentimeterValidated;
  else if (result.export_allowed) result.grade = MetricAccuracyGrade::kApproximate;
  return result;
}

MeshValidationResult validateMesh(const ReconstructionMesh& mesh,
                                  const MeshValidationLimits& limits) {
  if (limits.max_vertices == 0 || limits.max_triangles == 0)
    return {false, "invalid mesh limits"};
  if (mesh.positions_m.empty()) return {false, "mesh has no vertices"};
  if (mesh.positions_m.size() > limits.max_vertices) return {false, "vertex budget exceeded"};
  if (mesh.triangle_indices.empty() || mesh.triangle_indices.size() % 3 != 0)
    return {false, "indices must contain triangles"};
  if (mesh.triangle_indices.size() / 3 > limits.max_triangles)
    return {false, "triangle budget exceeded"};
  if (!mesh.normals.empty() && mesh.normals.size() != mesh.positions_m.size())
    return {false, "normal count differs from vertex count"};
  if (!mesh.texcoords.empty() && mesh.texcoords.size() != mesh.positions_m.size())
    return {false, "UV count differs from vertex count"};
  if (!mesh.base_color_texture.uri.empty() && mesh.texcoords.empty())
    return {false, "textured mesh has no UV coordinates"};
  for (const auto& value : mesh.positions_m)
    if (!finite(value.x) || !finite(value.y) || !finite(value.z))
      return {false, "vertex contains non-finite value"};
  for (const auto& value : mesh.normals) {
    const double length = std::sqrt(static_cast<double>(value.x) * value.x +
                                    static_cast<double>(value.y) * value.y +
                                    static_cast<double>(value.z) * value.z);
    if (!finite(value.x) || !finite(value.y) || !finite(value.z) || length < 1e-6)
      return {false, "normal is invalid"};
  }
  for (const auto& value : mesh.texcoords)
    if (!finite(value.x) || !finite(value.y)) return {false, "UV contains non-finite value"};
  for (const auto index : mesh.triangle_indices)
    if (index >= mesh.positions_m.size()) return {false, "index exceeds vertex count"};
  return {true, {}};
}

GltfAsset exportGltf(const ReconstructionMesh& mesh, const std::string& bin_name) {
  const auto validation = validateMesh(mesh);
  if (!validation.valid) throw std::invalid_argument(validation.error);
  if (bin_name.empty()) throw std::invalid_argument("glTF binary file name is empty");
  if (!littleEndianHost()) throw std::runtime_error("glTF export requires little-endian host encoding");
  GltfAsset asset;
  const std::size_t position_offset = append(asset.binary, mesh.positions_m);
  const std::size_t normal_offset = mesh.normals.empty() ? 0 : append(asset.binary, mesh.normals);
  const std::size_t uv_offset = mesh.texcoords.empty() ? 0 : append(asset.binary, mesh.texcoords);
  const std::size_t index_offset = append(asset.binary, mesh.triangle_indices);
  Vec3f minimum = mesh.positions_m.front(), maximum = minimum;
  for (const auto& p : mesh.positions_m) {
    minimum.x = std::min(minimum.x, p.x); minimum.y = std::min(minimum.y, p.y);
    minimum.z = std::min(minimum.z, p.z); maximum.x = std::max(maximum.x, p.x);
    maximum.y = std::max(maximum.y, p.y); maximum.z = std::max(maximum.z, p.z);
  }
  const std::size_t pos_bytes = mesh.positions_m.size() * sizeof(Vec3f);
  const std::size_t normal_bytes = mesh.normals.size() * sizeof(Vec3f);
  const std::size_t uv_bytes = mesh.texcoords.size() * sizeof(Vec2f);
  const std::size_t index_bytes = mesh.triangle_indices.size() * sizeof(std::uint32_t);
  std::vector<std::string> views;
  auto view = [&](std::size_t offset, std::size_t bytes, int target) {
    std::ostringstream s; s << "{\"buffer\":0,\"byteOffset\":" << offset
      << ",\"byteLength\":" << bytes << ",\"target\":" << target << "}";
    views.push_back(s.str()); return views.size() - 1;
  };
  const auto pos_view = view(position_offset, pos_bytes, 34962);
  const auto normal_view = mesh.normals.empty() ? 0 : view(normal_offset, normal_bytes, 34962);
  const auto uv_view = mesh.texcoords.empty() ? 0 : view(uv_offset, uv_bytes, 34962);
  const auto index_view = view(index_offset, index_bytes, 34963);
  std::vector<std::string> accessors;
  std::ostringstream pos; pos << "{\"bufferView\":" << pos_view
    << ",\"componentType\":5126,\"count\":" << mesh.positions_m.size()
    << ",\"type\":\"VEC3\",\"min\":[" << minimum.x << ',' << minimum.y << ',' << minimum.z
    << "],\"max\":[" << maximum.x << ',' << maximum.y << ',' << maximum.z << "]}";
  accessors.push_back(pos.str()); const std::size_t pos_accessor = 0;
  std::size_t normal_accessor = 0, uv_accessor = 0;
  if (!mesh.normals.empty()) { normal_accessor = accessors.size(); accessors.push_back(
      "{\"bufferView\":" + std::to_string(normal_view) + ",\"componentType\":5126,\"count\":" +
      std::to_string(mesh.normals.size()) + ",\"type\":\"VEC3\"}"); }
  if (!mesh.texcoords.empty()) { uv_accessor = accessors.size(); accessors.push_back(
      "{\"bufferView\":" + std::to_string(uv_view) + ",\"componentType\":5126,\"count\":" +
      std::to_string(mesh.texcoords.size()) + ",\"type\":\"VEC2\"}"); }
  const std::size_t index_accessor = accessors.size(); accessors.push_back(
      "{\"bufferView\":" + std::to_string(index_view) + ",\"componentType\":5125,\"count\":" +
      std::to_string(mesh.triangle_indices.size()) + ",\"type\":\"SCALAR\"}");
  std::ostringstream json; json << std::setprecision(9)
    << "{\"asset\":{\"version\":\"2.0\",\"generator\":\"AR Kernel\"},"
    << "\"scene\":0,\"scenes\":[{\"nodes\":[0]}],\"nodes\":[{\"mesh\":0,\"name\":\""
    << escapeJson(mesh.name) << "\"}],\"buffers\":[{\"uri\":\"" << escapeJson(bin_name)
    << "\",\"byteLength\":" << asset.binary.size() << "}],\"bufferViews\":[";
  for (std::size_t i=0;i<views.size();++i) { if(i) json << ','; json << views[i]; }
  json << "],\"accessors\":[";
  for (std::size_t i=0;i<accessors.size();++i) { if(i) json << ','; json << accessors[i]; }
  json << "],\"meshes\":[{\"primitives\":[{\"attributes\":{\"POSITION\":" << pos_accessor;
  if (!mesh.normals.empty()) json << ",\"NORMAL\":" << normal_accessor;
  if (!mesh.texcoords.empty()) json << ",\"TEXCOORD_0\":" << uv_accessor;
  json << "},\"indices\":" << index_accessor;
  if (!mesh.base_color_texture.uri.empty()) json << ",\"material\":0";
  json << "}]}]";
  if (!mesh.base_color_texture.uri.empty()) json <<
    ",\"images\":[{\"uri\":\"" << escapeJson(mesh.base_color_texture.uri) <<
    "\",\"mimeType\":\"" << escapeJson(mesh.base_color_texture.mime_type) <<
    "\"}],\"textures\":[{\"source\":0}],\"materials\":[{\"pbrMetallicRoughness\":{"
    "\"baseColorTexture\":{\"index\":0},\"metallicFactor\":0,\"roughnessFactor\":1}}]";
  json << ",\"extras\":{\"units\":\"meter\",\"accuracy\":\""
       << gradeName(MetricAccuracyGrade::kUnverified) << "\"}}";
  asset.json = json.str(); return asset;
}

std::vector<std::uint8_t> exportGlb(const ReconstructionMesh& mesh) {
  constexpr const char* internal_name="__arkernel_internal.bin";
  GltfAsset asset=exportGltf(mesh,internal_name);
  const std::string uri="\"uri\":\""+std::string(internal_name)+"\",";
  const std::size_t position=asset.json.find(uri);
  if(position==std::string::npos)throw std::runtime_error("Internal glTF buffer URI missing");
  asset.json.erase(position,uri.size());
  while((asset.json.size()&3U)!=0U)asset.json.push_back(' ');
  while((asset.binary.size()&3U)!=0U)asset.binary.push_back(0);
  constexpr std::size_t header_bytes=12,chunk_header_bytes=8;
  const std::size_t total=header_bytes+chunk_header_bytes+asset.json.size()+
                          chunk_header_bytes+asset.binary.size();
  if(asset.json.size()>std::numeric_limits<std::uint32_t>::max()||
     asset.binary.size()>std::numeric_limits<std::uint32_t>::max()||
     total>std::numeric_limits<std::uint32_t>::max())
    throw std::length_error("GLB exceeds 32-bit container limit");
  std::vector<std::uint8_t> output;output.reserve(total);
  appendU32Le(output,0x46546C67U);appendU32Le(output,2U);
  appendU32Le(output,static_cast<std::uint32_t>(total));
  appendU32Le(output,static_cast<std::uint32_t>(asset.json.size()));
  appendU32Le(output,0x4E4F534AU);
  output.insert(output.end(),asset.json.begin(),asset.json.end());
  appendU32Le(output,static_cast<std::uint32_t>(asset.binary.size()));
  appendU32Le(output,0x004E4942U);
  output.insert(output.end(),asset.binary.begin(),asset.binary.end());
  return output;
}

std::string exportObj(const ReconstructionMesh& mesh, const std::string& material_name) {
  const auto validation = validateMesh(mesh); if (!validation.valid) throw std::invalid_argument(validation.error);
  std::ostringstream out; out << std::setprecision(9) << "# AR Kernel; units: meters\n";
  if (!mesh.base_color_texture.uri.empty()) out << "mtllib " << material_name << "\nusemtl material0\n";
  for (const auto& p : mesh.positions_m) out << "v " << p.x << ' ' << p.y << ' ' << p.z << "\n";
  for (const auto& uv : mesh.texcoords) out << "vt " << uv.x << ' ' << 1.0F-uv.y << "\n";
  for (const auto& n : mesh.normals) out << "vn " << n.x << ' ' << n.y << ' ' << n.z << "\n";
  for (std::size_t i=0;i<mesh.triangle_indices.size();i+=3) {
    out << "f";
    for (int j=0;j<3;++j) { const auto index=mesh.triangle_indices[i+j]+1; out << ' ' << index;
      if (!mesh.texcoords.empty() || !mesh.normals.empty()) { out << '/'; if(!mesh.texcoords.empty()) out << index;
        if(!mesh.normals.empty()) out << '/' << index; } }
    out << "\n";
  } return out.str();
}

std::string exportMtl(const ReconstructionMesh& mesh) {
  std::ostringstream out; out << "newmtl material0\nKd 1 1 1\nKa 0 0 0\nKs 0 0 0\nillum 1\n";
  if (!mesh.base_color_texture.uri.empty()) out << "map_Kd " << mesh.base_color_texture.uri << "\n";
  return out.str();
}

std::string exportPly(const ReconstructionMesh& mesh) {
  const auto validation = validateMesh(mesh); if (!validation.valid) throw std::invalid_argument(validation.error);
  std::ostringstream out; out << std::setprecision(9) << "ply\nformat ascii 1.0\ncomment units meter\nelement vertex "
    << mesh.positions_m.size() << "\nproperty float x\nproperty float y\nproperty float z\n";
  if (!mesh.normals.empty()) out << "property float nx\nproperty float ny\nproperty float nz\n";
  out << "element face " << mesh.triangle_indices.size()/3
      << "\nproperty list uchar uint vertex_indices\nend_header\n";
  for (std::size_t i=0;i<mesh.positions_m.size();++i) { const auto& p=mesh.positions_m[i]; out << p.x<<' '<<p.y<<' '<<p.z;
    if(!mesh.normals.empty()){const auto& n=mesh.normals[i];out<<' '<<n.x<<' '<<n.y<<' '<<n.z;} out<<'\n'; }
  for(std::size_t i=0;i<mesh.triangle_indices.size();i+=3) out<<"3 "<<mesh.triangle_indices[i]<<' '<<mesh.triangle_indices[i+1]<<' '<<mesh.triangle_indices[i+2]<<'\n';
  return out.str();
}

}  // namespace arkernel

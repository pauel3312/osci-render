#include "PerspectiveEffect.h"
#include <numbers>

PerspectiveEffect::PerspectiveEffect() {}

Vector2 PerspectiveEffect::apply(int index, Vector2 input, const std::vector<double>& values, double sampleRate) {
	auto effectScale = values[0];
	auto depth = 1.0 + (values[1] - 0.1) * 3;
	auto rotateSpeed = linearSpeedToActualSpeed(values[2]);
	auto baseRotateX = values[3] * std::numbers::pi;
	auto baseRotateY = values[4] * std::numbers::pi;
	auto baseRotateZ = values[5] * std::numbers::pi;

	currentRotateX += baseRotateX * rotateSpeed;
	currentRotateY += baseRotateY * rotateSpeed;
	currentRotateZ += baseRotateZ * rotateSpeed;

	auto x = input.x;
	auto y = input.y;
	auto z = 0.0;

	parser.setVariable("x", x);
	parser.setVariable("y", y);
	parser.setVariable("z", z);

	auto result = parser.run();
	if (result.size() >= 3) {
		x = result[0];
		y = result[1];
		z = result[2];
	}

	auto rotateX = baseRotateX + currentRotateX;
	auto rotateY = baseRotateY + currentRotateY;
	auto rotateZ = baseRotateZ + currentRotateZ;

	// rotate around x-axis
	double cosValue = std::cos(rotateX);
	double sinValue = std::sin(rotateX);
	double y2 = cosValue * y - sinValue * z;
	double z2 = sinValue * y + cosValue * z;

	// rotate around y-axis
	cosValue = std::cos(rotateY);
	sinValue = std::sin(rotateY);
	double x2 = cosValue * x + sinValue * z2;
	double z3 = -sinValue * x + cosValue * z2;

	// rotate around z-axis
	cosValue = cos(rotateZ);
	sinValue = sin(rotateZ);
	double x3 = cosValue * x2 - sinValue * y2;
	double y3 = sinValue * x2 + cosValue * y2;

	// perspective projection
	auto focalLength = 1.0;
	return Vector2(
		(1 - effectScale) * input.x + effectScale * (x3 * focalLength / (z3 - depth)),
		(1 - effectScale) * input.y + effectScale * (y3 * focalLength / (z3 - depth))
	);
}

//! Gradient-boosted trees with logistic loss. Each tree fits the errors left by those before it,
//! using the second-order gain from XGBoost and histogram splits as in LightGBM.

#[derive(Debug, Clone, Copy)]
pub struct Params {
    pub trees: usize,
    pub depth: usize,
    pub learning_rate: f64,
    /// Shrinks leaves that rest on little evidence.
    pub lambda: f64,
    pub min_child_weight: f64,
    pub bins: usize,
}

impl Default for Params {
    fn default() -> Self {
        Params { trees: 200, depth: 4, learning_rate: 0.1, lambda: 1.0, min_child_weight: 1.0, bins: 32 }
    }
}

#[derive(Debug, Clone)]
enum Node {
    Leaf(f64),
    Split { feature: usize, threshold: f64, left: usize, right: usize },
}

#[derive(Debug, Clone)]
pub struct Model {
    base: f64,
    learning_rate: f64,
    trees: Vec<Vec<Node>>,
}

fn sigmoid(x: f64) -> f64 {
    1.0 / (1.0 + (-x).exp())
}

/// Quantile cut points, repeats removed. A value goes left when it is at or below a cut.
fn cut_points(values: &mut [f64], bins: usize) -> Vec<f64> {
    values.sort_by(f64::total_cmp);
    let mut cuts: Vec<f64> = (1..bins).map(|i| values[i * values.len() / bins]).collect();
    cuts.dedup();
    // The top value would send everything left and split nothing.
    if cuts.last() == values.last() {
        cuts.pop();
    }
    cuts
}

struct Builder<'a> {
    binned: &'a [Vec<u8>],
    cuts: &'a [Vec<f64>],
    gradients: &'a [f64],
    hessians: &'a [f64],
    params: Params,
}

impl Builder<'_> {
    fn leaf(&self, rows: &[usize]) -> Node {
        let g: f64 = rows.iter().map(|&r| self.gradients[r]).sum();
        let h: f64 = rows.iter().map(|&r| self.hessians[r]).sum();
        Node::Leaf(-g / (h + self.params.lambda))
    }

    /// The feature and cut with the highest gain, if any split improves on not splitting.
    fn best_split(&self, rows: &[usize]) -> Option<(usize, usize)> {
        let (lambda, min_weight) = (self.params.lambda, self.params.min_child_weight);
        let total_g: f64 = rows.iter().map(|&r| self.gradients[r]).sum();
        let total_h: f64 = rows.iter().map(|&r| self.hessians[r]).sum();
        let parent = total_g * total_g / (total_h + lambda);
        let mut best = None;
        let mut best_gain = 1e-9;
        for (feature, cuts) in self.cuts.iter().enumerate() {
            if cuts.is_empty() {
                continue;
            }
            let mut g_bins = vec![0.0; cuts.len() + 1];
            let mut h_bins = vec![0.0; cuts.len() + 1];
            for &row in rows {
                let bin = self.binned[row][feature] as usize;
                g_bins[bin] += self.gradients[row];
                h_bins[bin] += self.hessians[row];
            }
            let (mut left_g, mut left_h) = (0.0, 0.0);
            for cut in 0..cuts.len() {
                left_g += g_bins[cut];
                left_h += h_bins[cut];
                let (right_g, right_h) = (total_g - left_g, total_h - left_h);
                if left_h < min_weight || right_h < min_weight {
                    continue;
                }
                let gain = left_g * left_g / (left_h + lambda) + right_g * right_g / (right_h + lambda) - parent;
                if gain > best_gain {
                    best_gain = gain;
                    best = Some((feature, cut));
                }
            }
        }
        best
    }

    fn grow(&self, nodes: &mut Vec<Node>, rows: Vec<usize>, depth: usize) -> usize {
        let at = nodes.len();
        nodes.push(Node::Leaf(0.0));
        let split = if depth < self.params.depth { self.best_split(&rows) } else { None };
        let Some((feature, cut)) = split else {
            nodes[at] = self.leaf(&rows);
            return at;
        };
        let (left_rows, right_rows): (Vec<usize>, Vec<usize>) = rows.iter().partition(|&&r| self.binned[r][feature] as usize <= cut);
        let left = self.grow(nodes, left_rows, depth + 1);
        let right = self.grow(nodes, right_rows, depth + 1);
        nodes[at] = Node::Split { feature, threshold: self.cuts[feature][cut], left, right };
        at
    }
}

/// `weights` let each class count equally however lopsided the data is.
pub fn fit(x: &[Vec<f64>], y: &[bool], weights: &[f64], params: Params) -> Model {
    assert!(!x.is_empty() && x.len() == y.len() && x.len() == weights.len());
    assert!(params.bins >= 2 && params.bins <= 256);
    let features = x[0].len();
    let cuts: Vec<Vec<f64>> = (0..features)
        .map(|f| cut_points(&mut x.iter().map(|row| row[f]).collect::<Vec<_>>(), params.bins))
        .collect();
    let binned: Vec<Vec<u8>> = x
        .iter()
        .map(|row| row.iter().zip(&cuts).map(|(value, cut)| cut.partition_point(|c| c < value) as u8).collect())
        .collect();

    let positive: f64 = y.iter().zip(weights).filter(|(label, _)| **label).map(|(_, w)| *w).sum();
    let total: f64 = weights.iter().sum();
    let rate = (positive / total).clamp(1e-6, 1.0 - 1e-6);
    let base = (rate / (1.0 - rate)).ln();

    let mut scores = vec![base; x.len()];
    let mut model = Model { base, learning_rate: params.learning_rate, trees: Vec::with_capacity(params.trees) };
    let mut gradients = vec![0.0; x.len()];
    let mut hessians = vec![0.0; x.len()];
    for _ in 0..params.trees {
        for row in 0..x.len() {
            let p = sigmoid(scores[row]);
            gradients[row] = weights[row] * (p - f64::from(u8::from(y[row])));
            hessians[row] = weights[row] * (p * (1.0 - p)).max(1e-9);
        }
        let builder = Builder { binned: &binned, cuts: &cuts, gradients: &gradients, hessians: &hessians, params };
        let mut nodes = Vec::new();
        builder.grow(&mut nodes, (0..x.len()).collect(), 0);
        for (row, score) in scores.iter_mut().enumerate() {
            *score += params.learning_rate * tree_value(&nodes, &x[row]);
        }
        model.trees.push(nodes);
    }
    model
}

fn tree_value(nodes: &[Node], row: &[f64]) -> f64 {
    let mut at = 0;
    loop {
        match nodes[at] {
            Node::Leaf(value) => return value,
            Node::Split { feature, threshold, left, right } => at = if row[feature] <= threshold { left } else { right },
        }
    }
}

impl Model {
    /// A score from 0 to 1. With class weights it is a ranking, not a true probability.
    pub fn score(&self, row: &[f64]) -> f64 {
        sigmoid(self.base + self.learning_rate * self.trees.iter().map(|tree| tree_value(tree, row)).sum::<f64>())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn grid() -> (Vec<Vec<f64>>, Vec<bool>) {
        let mut x = Vec::new();
        for i in 0..40 {
            for j in 0..40 {
                x.push(vec![f64::from(i) / 40.0, f64::from(j) / 40.0]);
            }
        }
        let y = x.iter().map(|row| (row[0] > 0.5) != (row[1] > 0.5)).collect();
        (x, y)
    }

    #[test]
    fn learns_a_pattern_no_single_feature_shows() {
        let (x, y) = grid();
        let model = fit(&x, &y, &vec![1.0; x.len()], Params { trees: 50, ..Params::default() });
        let right = x.iter().zip(&y).filter(|(row, label)| (model.score(row) > 0.5) == **label).count();
        assert!(right as f64 / x.len() as f64 > 0.97, "{right} of {}", x.len());
    }

    #[test]
    fn class_weights_stop_the_larger_class_winning_by_default() {
        let x: Vec<Vec<f64>> = (0..1000).map(|i| vec![f64::from(i) / 1000.0]).collect();
        // The minority sits where the majority also appears, so unweighted the majority wins there.
        let y: Vec<bool> = (0..1000).map(|i| i % 10 != 0).collect();
        let plain = fit(&x, &y, &vec![1.0; 1000], Params { trees: 5, ..Params::default() });
        let weights: Vec<f64> = y.iter().map(|label| if *label { 1.0 } else { 9.0 }).collect();
        let balanced = fit(&x, &y, &weights, Params { trees: 5, ..Params::default() });
        let mean = |m: &Model| x.iter().map(|row| m.score(row)).sum::<f64>() / 1000.0;
        assert!(mean(&plain) > 0.85);
        assert!((mean(&balanced) - 0.5).abs() < 0.1);
    }

    #[test]
    fn the_same_data_gives_the_same_model() {
        let (x, y) = grid();
        let params = Params { trees: 10, ..Params::default() };
        let (a, b) = (fit(&x, &y, &vec![1.0; x.len()], params), fit(&x, &y, &vec![1.0; x.len()], params));
        assert_eq!(a.score(&x[7]), b.score(&x[7]));
    }

    #[test]
    fn a_feature_that_never_varies_is_ignored() {
        let x: Vec<Vec<f64>> = (0..100).map(|i| vec![1.0, f64::from(i)]).collect();
        let y: Vec<bool> = (0..100).map(|i| i >= 50).collect();
        let model = fit(&x, &y, &vec![1.0; 100], Params { trees: 20, ..Params::default() });
        assert!(model.score(&[1.0, 90.0]) > 0.9 && model.score(&[1.0, 10.0]) < 0.1);
    }
}

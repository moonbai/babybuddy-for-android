package eu.pkgsoftware.babybuddywidgets;

import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.RecyclerView;
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerBinding;
import eu.pkgsoftware.babybuddywidgets.databinding.BabyManagerAlternativeBinding;
import eu.pkgsoftware.babybuddywidgets.networking.BabyBuddyClient;
import eu.pkgsoftware.babybuddywidgets.networking.ChildrenStateTracker;

class BabyPagerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    private List<RecyclerView.ViewHolder> holders = new ArrayList<>();
    private RecyclerView.ViewHolder activeHolder = null;
    private static final int VIEW_TYPE_OFFICIAL = 0;
    private static final int VIEW_TYPE_ALTERNATIVE = 1;

    private BaseFragment fragment = null;
    private BabyBuddyClient.Child[] children = null;
    private ChildrenStateTracker stateTracker = null;

    public void postInit(
        BaseFragment fragment,
        BabyBuddyClient.Child[] children,
        ChildrenStateTracker stateTracker
    ) {
        this.fragment = fragment;
        this.stateTracker = stateTracker;
        updateChildren(children);
    }

    public void updateChildren(BabyBuddyClient.Child[] children) {
        this.children = children;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        String style = PreferenceManager.getDefaultSharedPreferences(
            fragment.getContext()
        ).getString("setting_interface_style", "official");
        return "alternative".equals(style) ? VIEW_TYPE_ALTERNATIVE : VIEW_TYPE_OFFICIAL;
    }

    @Override
    public @NonNull
    RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == VIEW_TYPE_ALTERNATIVE) {
            BabyManagerAlternativeBinding babyBinding = BabyManagerAlternativeBinding.inflate(
                fragment.getLayoutInflater(), null, false
            );
            View v = babyBinding.getRoot();
            v.setLayoutParams(new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));

            AlternativeBabyLayoutHolderKotlin holder = new AlternativeBabyLayoutHolderKotlin(fragment, babyBinding);
            holders.add(holder);
            return holder;
        } else {
            BabyManagerBinding babyBinding = BabyManagerBinding.inflate(
                fragment.getLayoutInflater(), null, false
            );
            View v = babyBinding.getRoot();
            v.setLayoutParams(new RelativeLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            ));

            BabyLayoutHolder holder = new BabyLayoutHolder(fragment, babyBinding);
            holders.add(holder);
            return holder;
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof BabyLayoutHolder) {
            ((BabyLayoutHolder) holder).updateChild(children[position], stateTracker);
        } else if (holder instanceof AlternativeBabyLayoutHolderKotlin) {
            ((AlternativeBabyLayoutHolderKotlin) holder).updateChild(children[position], stateTracker);
        }

        int childIndex = LoggedInFragment.childIndexBySlug(
            children,
            fragment.getMainActivity().getCredStore().getSelectedChild()
        );
        if (childIndex >= 0) {
            if (Objects.equals(children[position], children[childIndex])) {
                activeViewChanged(children[position]);
            }
        }
    }

    @Override
    public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
        if (holder instanceof BabyLayoutHolder) {
            ((BabyLayoutHolder) holder).clear();
        } else if (holder instanceof AlternativeBabyLayoutHolderKotlin) {
            ((AlternativeBabyLayoutHolderKotlin) holder).clear();
        }
    }

    @Override
    public int getItemCount() {
        if (children == null) {
            return 0;
        }
        return children.length;
    }

    public void activeViewChanged(BabyBuddyClient.Child c) {
        activeHolder = null;
        for (RecyclerView.ViewHolder h : holders) {
            if (h instanceof BabyLayoutHolder) {
                BabyLayoutHolder holder = (BabyLayoutHolder) h;
                if (Objects.equals(c, holder.getChild())) {
                    holder.updateChild(c, stateTracker);
                    activeHolder = h;
                } else {
                    holder.onViewDeselected();
                }
            } else if (h instanceof AlternativeBabyLayoutHolderKotlin) {
                AlternativeBabyLayoutHolderKotlin holder = (AlternativeBabyLayoutHolderKotlin) h;
                if (Objects.equals(c, holder.getChild())) {
                    holder.updateChild(c, stateTracker);
                    activeHolder = h;
                } else {
                    holder.onViewDeselected();
                }
            }
        }
    }

    public RecyclerView.ViewHolder getActive() {
        return activeHolder;
    }

    public void close() {
        for (RecyclerView.ViewHolder h : holders) {
            if (h instanceof BabyLayoutHolder) {
                ((BabyLayoutHolder) h).close();
            } else if (h instanceof AlternativeBabyLayoutHolderKotlin) {
                ((AlternativeBabyLayoutHolderKotlin) h).close();
            }
        }
    }
}

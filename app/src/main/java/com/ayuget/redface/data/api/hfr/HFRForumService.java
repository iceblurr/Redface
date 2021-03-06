/*
 * Copyright 2015 Ayuget
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ayuget.redface.data.api.hfr;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.ayuget.redface.data.api.MDAuthenticator;
import com.ayuget.redface.data.api.MDEndpoints;
import com.ayuget.redface.data.api.MDMessageSender;
import com.ayuget.redface.data.api.MDService;
import com.ayuget.redface.data.api.SmileyService;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToBBCode;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToPostList;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToPrivateMessageList;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToProfile;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToSmileyList;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToTopic;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToTopicList;
import com.ayuget.redface.data.api.model.Category;
import com.ayuget.redface.data.api.model.Post;
import com.ayuget.redface.data.api.model.PrivateMessage;
import com.ayuget.redface.data.api.model.Profile;
import com.ayuget.redface.data.api.model.Response;
import com.ayuget.redface.data.api.model.Smiley;
import com.ayuget.redface.data.api.model.Subcategory;
import com.ayuget.redface.data.api.model.Topic;
import com.ayuget.redface.data.api.model.TopicFilter;
import com.ayuget.redface.data.api.model.User;
import com.ayuget.redface.data.api.hfr.transforms.HTMLToCategoryList;
import com.ayuget.redface.data.api.model.misc.SmileyResponse;
import com.ayuget.redface.data.state.CategoriesStore;
import com.ayuget.redface.network.HTTPClientProvider;
import com.ayuget.redface.network.PageFetcher;
import com.ayuget.redface.settings.RedfaceSettings;
import com.ayuget.redface.ui.UIConstants;
import com.ayuget.redface.ui.event.TopicPageCountUpdatedEvent;
import com.ayuget.redface.util.UserUtils;
import com.google.common.base.Optional;
import com.google.common.primitives.Ints;
import com.squareup.otto.Bus;

import java.util.List;

import javax.inject.Inject;

import retrofit.RestAdapter;
import retrofit.http.GET;
import retrofit.http.Path;
import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;
import timber.log.Timber;

/**
 * Dedicated service to parse pages from HFR's forum
 */
public class HFRForumService implements MDService {
    @Inject PageFetcher pageFetcher;

    @Inject PostsTweaker postsTweaker;

    @Inject MDEndpoints mdEndpoints;

    @Inject MDAuthenticator mdAuthenticator;

    @Inject HTTPClientProvider httpClientProvider;

    @Inject SmileyService smileyService;

    @Inject Bus bus;

    @Inject CategoriesStore categoriesStore;

    @Inject MDMessageSender mdMessageSender;

    @Inject RedfaceSettings appSettings;

    private String currentHashcheck;

    @Override
    public Observable<List<Category>> listCategories(final User user) {
        Timber.d("Retrieving categories for user '%s'", user.getUsername());

        List<Category> cachedCategories = categoriesStore.getCategories(user);

        if (cachedCategories == null) {
            return pageFetcher.fetchSource(user, mdEndpoints.homepage())
                    .map(new HTMLToCategoryList())
                    .map(new Func1<List<Category>, List<Category>>() {
                        @Override
                        public List<Category> call(List<Category> categories) {
                            Timber.d("Successfully retrieved '%d' categories from network for user '%s', caching them", categories.size(), user.getUsername());
                            categoriesStore.storeCategories(user, categories);
                            return categories;
                        }
                    });
        }
        else {
            Timber.d("Successfully retrieved '%d' categories from cache for user '%s'", cachedCategories.size(), user.getUsername());
            return Observable.just(cachedCategories);
        }
    }

    private String getTopicListEndpoint(final Category category, final Subcategory subcategory, int page, final TopicFilter filter) {
        if (subcategory == null) {
            return mdEndpoints.category(category, page, filter);
        }
        else {
            return mdEndpoints.subcategory(category, subcategory, page, filter);
        }
    }

    @Override
    public Observable<List<Topic>> listTopics(User user, final Category category, final Subcategory subcategory, int page, final TopicFilter filter) {
        return pageFetcher.fetchSource(user, getTopicListEndpoint(category, subcategory, page, filter))
                .map(new HTMLToTopicList(categoriesStore))
                .flatMap(new Func1<List<Topic>, Observable<Topic>>() {
                    @Override
                    public Observable<Topic> call(List<Topic> topics) {
                        return Observable.from(topics);
                    }
                })
                .filter(new Func1<Topic, Boolean>() {
                    @Override
                    public Boolean call(Topic topic) {
                        return topic.hasUnreadPosts() || appSettings.showFullyReadTopics();
                    }
                })
                .map(new Func1<Topic, Topic>() {
                    @Override
                    public Topic call(Topic topic) {
                        topic.setCategory(category);
                        return topic;
                    }
                })
                .toList();
    }

    @Override
    public Observable<List<Topic>> listMetaPageTopics(User user, TopicFilter filter, boolean sortByDate) {
        Observable<Topic> metaPageTopics =
                pageFetcher.fetchSource(user, mdEndpoints.metaPage(filter))
                .map(new HTMLToTopicList(categoriesStore))
                .flatMap(new Func1<List<Topic>, Observable<Topic>>() {
                    @Override
                    public Observable<Topic> call(List<Topic> topics) {
                        return Observable.from(topics);
                    }
                })
                .filter(new Func1<Topic, Boolean>() {
                    @Override
                    public Boolean call(Topic topic) {
                        return topic.hasUnreadPosts() || appSettings.showFullyReadTopics();
                    }
                });

        if (sortByDate) {
            return metaPageTopics.toSortedList(new Func2<Topic, Topic, Integer>() {
                @Override
                public Integer call(Topic topic, Topic topic2) {
                    return topic2.getLastPostDate().compareTo(topic.getLastPostDate());
                }
            });
        }
        else {
            return metaPageTopics.toList();
        }
    }

    @Override
    public Observable<List<Post>> listPosts(User user, final Topic topic, final int page) {
        return pageFetcher.fetchSource(user, mdEndpoints.topic(topic, page))
                .map(new Func1<String, String>() {
                    @Override
                    public String call(String htmlSource) {
                        // Hashcheck is needed by the server to post new content
                        currentHashcheck = HashcheckExtractor.extract(htmlSource);
                        return htmlSource;
                    }
                })
                .map(new HTMLToPostList()) // Convert HTML source to objects
                .map(new Func1<List<Post>, List<Post>>() {
                    @Override
                    public List<Post> call(List<Post> posts) {
                        // Last post of previous page is automatically put in first position of
                        // next page. This can be annoying...
                        if (!appSettings.showPreviousPageLastPost() && page > 1 && posts.size() > 1) {
                            posts.remove(0);
                            return posts;
                        }
                        else {
                            return posts;
                        }
                    }
                })
                .map(new Func1<List<Post>, List<Post>>() {
                    @Override
                    public List<Post> call(final List<Post> posts) {
                        if (posts.size() > 0) {
                            int newTopicPagesCount = posts.get(0).getTopicPagesCount();

                            // If the topic pages count is known and different from the one we have,
                            // it usually means new pages have been added since. The event emitted
                            // below can be catched by the UI to update itself.
                            if (newTopicPagesCount != UIConstants.UNKNOWN_PAGES_COUNT && newTopicPagesCount != topic.getPagesCount()) {
                                new Handler(Looper.getMainLooper()).post(new Runnable() {
                                    @Override
                                    public void run() {
                                        bus.post(new TopicPageCountUpdatedEvent(topic, posts.get(0).getTopicPagesCount()));
                                    }
                                });
                            }
                        }
                        return posts;
                    }
                })
                .map(postsTweaker);
    }

    @Override
    public Observable<String> getQuote(User user, Topic topic, int postId) {
        return pageFetcher.fetchSource(user, mdEndpoints.quote(topic.getCategory(), topic, postId))
                .map(new HTMLToBBCode());
    }

    @Override
    public Observable<String> getPostContent(User user, Topic topic, int postId) {
        return pageFetcher.fetchSource(user, mdEndpoints.editPost(topic.getCategory(), topic, postId))
                .map(new HTMLToBBCode());
    }

    @Override
    public Observable<List<Smiley>> getRecentlyUsedSmileys(User user) {
        if (user.isGuest()) {
            return Observable.empty();
        }
        else {
            Optional<Integer> userId = UserUtils.getLoggedInUserId(user, httpClientProvider.getClientForUser(user));

            if (userId.isPresent()) {
                return smileyService.getUserSmileys(userId.get()).map(new Func1<SmileyResponse, List<Smiley>>() {
                    @Override
                    public List<Smiley> call(SmileyResponse smileyResponse) {
                        return smileyResponse.getSmileys();
                    }
                });
            }
            else {
                return Observable.empty();
            }
        }
    }

    @Override
    public Observable<Topic> getTopic(User user, Category category, int topicId) {
        return pageFetcher.fetchSource(user, mdEndpoints.topic(category, topicId))
                .map(new HTMLToTopic());
    }

    @Override
    public Observable<List<Smiley>> getPopularSmileys() {
        return smileyService.getPopularSmileys().map(new Func1<SmileyResponse, List<Smiley>>() {
            @Override
            public List<Smiley> call(SmileyResponse smileyResponse) {
                return smileyResponse.getSmileys();
            }
        });
    }

    @Override
    public Observable<List<Smiley>> searchSmileys(User user, String searchExpression) {
        // http://forum.hardware.fr/message-smi-mp-aj.php?config=hfr.inc&findsmilies=fed
        return pageFetcher.fetchSource(user, mdEndpoints.smileySearch(searchExpression))
                .map(new HTMLToSmileyList());
    }

    @Override
    public Observable<Response> replyToTopic(User user, Topic topic, String message, boolean includeSignature) {
        return mdMessageSender.replyToTopic(user, topic, message, currentHashcheck, includeSignature);
    }

    @Override
    public Observable<Response> editPost(User user, Topic topic, int postId, String newMessage, boolean includeSignature) {
        return mdMessageSender.editPost(user, topic, postId, newMessage, currentHashcheck, includeSignature);
    }

    @Override
    public Observable<Response> sendNewPrivateMessage(User user, String subject, String recipientUsername, String message, boolean includeSignature) {
        return mdMessageSender.sendNewPrivateMessage(user, subject, recipientUsername, message, currentHashcheck, includeSignature);
    }

    @Override
    public Observable<Boolean> markPostAsFavorite(User user, Topic topic, int postId) {
        return mdMessageSender.markPostAsFavorite(user, topic, postId);
    }

    @Override
    public Observable<List<PrivateMessage>> listPrivateMessages(User user, int page) {
        return pageFetcher.fetchSource(user, mdEndpoints.privateMessages(page))
                .map(new Func1<String, String>() {
                    @Override
                    public String call(String htmlSource) {
                        // Hashcheck is needed by the server to post new content
                        currentHashcheck = HashcheckExtractor.extract(htmlSource);
                        return htmlSource;
                    }
                })
                .map(new HTMLToPrivateMessageList());
    }

    @Override
    public Observable<List<PrivateMessage>> getNewPrivateMessages(User user) {
        return listPrivateMessages(user, 1)
                .flatMap(new Func1<List<PrivateMessage>, Observable<PrivateMessage>>() {
                    @Override
                    public Observable<PrivateMessage> call(List<PrivateMessage> privateMessages) {
                        return Observable.from(privateMessages);
                    }
                })
                .filter(new Func1<PrivateMessage, Boolean>() {
                    @Override
                    public Boolean call(PrivateMessage privateMessage) {
                        return privateMessage.hasUnreadMessages();
                    }
                })
                .toList();
    }

    @Override
    public Observable<Boolean> reportPost(User user, Topic topic, int postId) {
        return null;
    }

    @Override
    public Observable<Boolean> deletePost(User user, Topic topic, int postId) {
        return mdMessageSender.deletePost(user, topic, postId, currentHashcheck);
    }

    @Override
    public Observable<Profile> getProfile(User user, int user_id) {
        Timber.d("Retrieving profile for user id '%d'", user_id);

        return pageFetcher.fetchSource(user, mdEndpoints.profile(user_id))
                .map(new HTMLToProfile());
    }
}

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

package com.ayuget.redface.ui.template;

import android.content.Context;
import android.text.TextUtils;

import com.ayuget.redface.data.api.model.Post;


public class AvatarTemplate extends HTMLTemplate<Post> {
    private static final String LOG_TAG = AvatarTemplate.class.getSimpleName();

    private static final String AVATAR_TEMPLATE = "avatar.html";

    public AvatarTemplate(Context context) {
        super(context, AVATAR_TEMPLATE);
    }

    @Override
    protected void render(Post post, String templateContent, StringBuilder stream) {
        if (post.getAvatarUrl() == null) {
            stream.append("");
        }
        else {
            stream.append(TextUtils.replace(
                    templateContent,
                    new String[]{"{avatarUrl}"},
                    new String[]{post.getAvatarUrl()}
            ));
        }
    }
}
